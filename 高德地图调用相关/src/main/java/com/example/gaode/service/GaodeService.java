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
     * 获取指定区县下所有乡镇（使用地理编码获取精确坐标）
     */
    public List<TownshipInfo> getTownshipsByDistrict(String districtName, String adCode) throws IOException, InterruptedException {
        System.out.println("正在获取 " + districtName + " 下的乡镇...");
        JsonNode root = requestDistrictApi(adCode, 2);

        // 第一步：收集乡镇名称列表
        List<String[]> townshipNames = new ArrayList<>(); // [name, adcode, level]
        if ("1".equals(root.path("status").asText())) {
            JsonNode items = root.path("districts");
            if (items.isArray() && items.size() > 0) {
                JsonNode districtNode = items.get(0);
                JsonNode subDistricts = districtNode.path("districts");
                if (subDistricts.isArray()) {
                    for (JsonNode d : subDistricts) {
                        String name = d.path("name").asText();
                        String level = d.path("level").asText();

                        // 乡镇/街道
                        if ("street".equals(level)) {
                            townshipNames.add(new String[]{name, d.path("adcode").asText(), level});
                        }

                        // 如果还有下级（比如街道下面还有社区），继续递归
                        JsonNode subSub = d.path("districts");
                        if (subSub.isArray() && subSub.size() > 0 && !"street".equals(level)) {
                            for (JsonNode ss : subSub) {
                                String ssLevel = ss.path("level").asText();
                                if ("street".equals(ssLevel)) {
                                    townshipNames.add(new String[]{ss.path("name").asText(),
                                            ss.path("adcode").asText(), ssLevel});
                                }
                            }
                        }
                    }
                }
            }
        }

        System.out.println("  " + districtName + " 找到 " + townshipNames.size() + " 个乡镇/街道，开始获取精确坐标...");

        // 第二步：使用地理编码API获取每个乡镇的精确坐标
        List<TownshipInfo> townships = new ArrayList<>();
        for (int i = 0; i < townshipNames.size(); i++) {
            String[] item = townshipNames.get(i);
            String name = item[0];
            String adcode = item[1];
            String level = item[2];

            Thread.sleep(GaodeConfig.REQUEST_INTERVAL); // 请求间隔，避免限流

            // 调用地理编码API获取精确坐标
            TownshipInfo info = geocodeTownship(name, districtName);
            if (info != null) {
                info.setAdCode(adcode);
                info.setLevel(level);
                townships.add(info);
                System.out.printf("  [%d/%d] %s -> (%.6f, %.6f)%n",
                        i + 1, townshipNames.size(), name, info.getLongitude(), info.getLatitude());
            } else {
                // 地理编码失败，使用district API的坐标作为备用
                System.err.println("  [" + (i + 1) + "/" + townshipNames.size() + "] " + name + " 地理编码失败，跳过");
            }
        }

        System.out.println("  " + districtName + " 成功获取 " + townships.size() + " 个乡镇/街道坐标");
        return townships;
    }

    /**
     * 地理编码：根据乡镇名称获取精确坐标
     *
     * @param townshipName 乡镇名称
     * @param districtName 所属区县名称
     * @return TownshipInfo 包含坐标信息，失败返回null
     */
    private TownshipInfo geocodeTownship(String townshipName, String districtName) throws IOException, InterruptedException {
        String address = districtName + townshipName;
        String url = String.format("%s?key=%s&address=%s&city=%s",
                GaodeConfig.GEOCODE_URL,
                GaodeConfig.API_KEY,
                address,
                districtName);

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
                    return new TownshipInfo(townshipName, "", districtName, longitude, latitude, "street");
                }
            }
        }

        return null;
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
