package com.example.gaode.service;

import com.example.gaode.config.GaodeConfig;
import com.example.gaode.model.TownshipInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * 高德地图API服务
 */
public class GaodeService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GaodeService() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 请求行政区划API
     */
    private JsonNode requestDistrictApi(String keywords, int subdistrict) throws IOException, InterruptedException {
        String url = String.format("%s?key=%s&keywords=%s&subdistrict=%d&extensions=base",
                GaodeConfig.DISTRICT_URL,
                GaodeConfig.API_KEY,
                keywords,
                subdistrict);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    /**
     * 解析单个区域节点
     */
    private TownshipInfo parseDistrictNode(JsonNode district, String parentDistrict) {
        String name = district.path("name").asText();
        String adCode = district.path("adcode").asText();
        String level = district.path("level").asText();
        String center = district.path("center").asText();

        double longitude = 0, latitude = 0;
        if (center != null && !center.isEmpty()) {
            String[] coords = center.split(",");
            if (coords.length == 2) {
                longitude = Double.parseDouble(coords[0]);
                latitude = Double.parseDouble(coords[1]);
            }
        }

        return new TownshipInfo(name, adCode, parentDistrict, longitude, latitude, level);
    }

    /**
     * 获取达州市下所有区县
     */
    public List<TownshipInfo> getDazhouDistricts() throws IOException, InterruptedException {
        System.out.println("正在获取达州市下辖区县...");
        JsonNode root = requestDistrictApi(GaodeConfig.DAZHOU_AD_CODE, 1);

        List<TownshipInfo> districts = new ArrayList<>();
        if ("1".equals(root.path("status").asText())) {
            JsonNode items = root.path("districts");
            if (items.isArray() && items.size() > 0) {
                // 达州市本身的districts
                JsonNode dazhouNode = items.get(0);
                JsonNode subDistricts = dazhouNode.path("districts");
                if (subDistricts.isArray()) {
                    for (JsonNode d : subDistricts) {
                        String level = d.path("level").asText();
                        if ("district".equals(level)) {
                            districts.add(parseDistrictNode(d, d.path("name").asText()));
                        }
                    }
                }
            }
        }

        System.out.println("获取到 " + districts.size() + " 个区县");
        for (TownshipInfo d : districts) {
            System.out.println("  " + d.getName() + " (" + d.getAdCode() + ")");
        }
        return districts;
    }

    /**
     * 获取指定区县下所有乡镇
     */
    public List<TownshipInfo> getTownshipsByDistrict(String districtName, String adCode) throws IOException, InterruptedException {
        System.out.println("正在获取 " + districtName + " 下的乡镇...");
        JsonNode root = requestDistrictApi(adCode, 2);

        List<TownshipInfo> townships = new ArrayList<>();
        if ("1".equals(root.path("status").asText())) {
            JsonNode items = root.path("districts");
            if (items.isArray() && items.size() > 0) {
                JsonNode districtNode = items.get(0);
                JsonNode subDistricts = districtNode.path("districts");
                if (subDistricts.isArray()) {
                    for (JsonNode d : subDistricts) {
                        String name = d.path("name").asText();
                        String level = d.path("level").asText();
                        String center = d.path("center").asText();

                        double longitude = 0, latitude = 0;
                        if (center != null && !center.isEmpty()) {
                            String[] coords = center.split(",");
                            if (coords.length == 2) {
                                longitude = Double.parseDouble(coords[0]);
                                latitude = Double.parseDouble(coords[1]);
                            }
                        }

                        // 乡镇/街道
                        if ("street".equals(level)) {
                            townships.add(new TownshipInfo(name, d.path("adcode").asText(),
                                    districtName, longitude, latitude, level));
                        }

                        // 如果还有下级（比如街道下面还有社区），继续递归
                        JsonNode subSub = d.path("districts");
                        if (subSub.isArray() && subSub.size() > 0 && !"street".equals(level)) {
                            for (JsonNode ss : subSub) {
                                String ssLevel = ss.path("level").asText();
                                if ("street".equals(ssLevel)) {
                                    String ssCenter = ss.path("center").asText();
                                    double ssLng = 0, ssLat = 0;
                                    if (ssCenter != null && !ssCenter.isEmpty()) {
                                        String[] coords = ssCenter.split(",");
                                        if (coords.length == 2) {
                                            ssLng = Double.parseDouble(coords[0]);
                                            ssLat = Double.parseDouble(coords[1]);
                                        }
                                    }
                                    townships.add(new TownshipInfo(ss.path("name").asText(),
                                            ss.path("adcode").asText(), districtName, ssLng, ssLat, ssLevel));
                                }
                            }
                        }
                    }
                }
            }
        }

        System.out.println("  " + districtName + " 获取到 " + townships.size() + " 个乡镇/街道");
        return townships;
    }

    /**
     * 获取达州市所有乡镇
     */
    public List<TownshipInfo> getDazhouTownships() throws IOException, InterruptedException {
        System.out.println("========== 开始获取达州市所有乡镇坐标 ==========\n");

        // 1. 先获取所有区县
        List<TownshipInfo> districts = getDazhouDistricts();

        // 2. 逐个区县获取乡镇
        List<TownshipInfo> allTownships = new ArrayList<>();
        for (TownshipInfo district : districts) {
            Thread.sleep(GaodeConfig.REQUEST_INTERVAL); // 请求间隔，避免限流
            List<TownshipInfo> townships = getTownshipsByDistrict(district.getName(), district.getAdCode());
            allTownships.addAll(townships);
        }

        System.out.println("\n共获取 " + allTownships.size() + " 个乡镇/街道");
        return allTownships;
    }

    /**
     * 地理编码：根据地址获取坐标
     *
     * @param address 地址
     * @return TownshipInfo 包含坐标信息，失败返回null
     */
    public TownshipInfo geocode(String address) throws IOException, InterruptedException {
        String url = String.format("%s?key=%s&address=%s&city=达州",
                GaodeConfig.GEOCODE_URL,
                GaodeConfig.API_KEY,
                address);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        if ("1".equals(root.path("status").asText())) {
            JsonNode geocodes = root.path("geocodes");
            if (geocodes.isArray() && geocodes.size() > 0) {
                JsonNode geo = geocodes.get(0);
                String location = geo.path("location").asText();
                String[] coords = location.split(",");

                if (coords.length == 2) {
                    double longitude = Double.parseDouble(coords[0]);
                    double latitude = Double.parseDouble(coords[1]);
                    System.out.println("地理编码成功: " + address + " -> (" + longitude + ", " + latitude + ")");
                    // 使用传入的地址作为名称，而不是API返回的名称
                    return new TownshipInfo(address, "", "达州市", longitude, latitude, "warehouse");
                }
            }
        }

        System.err.println("地理编码失败: " + root.path("info").asText());
        return null;
    }
}
