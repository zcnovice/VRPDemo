package com.example.gaode;

import com.example.gaode.config.GaodeConfig;
import com.example.gaode.model.RouteDistance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 修复坐标及距离数据
 * 需要修复的节点：
 * 1. 朝阳街道：原 120.467130, 41.596732 -> 新 107.475585, 31.215538
 * 2. 渠北镇：原 121.777503, 41.588657 -> 新 106.963688, 30.876893
 */
public class FixChaoyangDistance {

    // 需要修复的节点配置
    private static final Map<String, double[]> FIX_NODES = new HashMap<>();
    static {
        // 朝阳街道：原坐标 -> 新坐标
        //FIX_NODES.put("朝阳街道", new double[]{107.475585, 31.215538});
        // 渠北镇：原坐标 -> 新坐标
        //FIX_NODES.put("渠北镇", new double[]{106.963688, 30.876893});
        // 渠江街道
        FIX_NODES.put("渠江街道", new double[]{106.971782, 30.838059});
    }

    private static final long REQUEST_INTERVAL = 334; // 每秒3次

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FixChaoyangDistance() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 节点信息
     */
    static class Node {
        String name;
        double lng;
        double lat;
        String district;
        String adCode;
        String level;

        Node(String name, double lng, double lat, String district, String adCode, String level) {
            this.name = name;
            this.lng = lng;
            this.lat = lat;
            this.district = district;
            this.adCode = adCode;
            this.level = level;
        }
    }

    public static void main(String[] args) {
        System.out.println("========== 修复坐标及距离数据 ==========");
        System.out.println("需要修复的节点:");
        for (Map.Entry<String, double[]> entry : FIX_NODES.entrySet()) {
            System.out.println("  - " + entry.getKey() + " -> (" + entry.getValue()[0] + ", " + entry.getValue()[1] + ")");
        }
        System.out.println();

        FixChaoyangDistance fixer = new FixChaoyangDistance();

        try {
            // 1. 读取所有节点
            List<Node> nodes = fixer.readNodesFromCsv("达州市乡镇坐标.csv");
            System.out.println("读取到 " + nodes.size() + " 个节点");

            // 2. 修复需要修改的节点坐标
            Set<String> fixedNames = new HashSet<>();
            for (Node node : nodes) {
                if (FIX_NODES.containsKey(node.name)) {
                    double[] newCoords = FIX_NODES.get(node.name);
                    System.out.println("找到 " + node.name + "，原坐标: (" + node.lng + ", " + node.lat + ")");
                    node.lng = newCoords[0];
                    node.lat = newCoords[1];
                    System.out.println("更新为: (" + node.lng + ", " + node.lat + ")");
                    fixedNames.add(node.name);
                }
            }

            if (fixedNames.isEmpty()) {
                System.err.println("未找到需要修复的节点！");
                return;
            }

            // 3. 保存修复后的坐标文件
            fixer.saveNodesToCsv(nodes, "达州市乡镇坐标.csv");
            fixer.saveNodesToSql(nodes, "达州市乡镇坐标.sql");
            System.out.println("坐标文件已更新");

            // 4. 读取现有的距离数据
            List<RouteDistance> existingRoutes = fixer.readRoutesFromCsv("达州市乡镇道路距离.csv");
            System.out.println("读取到 " + existingRoutes.size() + " 条现有距离记录");

            // 5. 删除需要修复节点相关的旧距离数据
            List<RouteDistance> filteredRoutes = new ArrayList<>();
            for (RouteDistance route : existingRoutes) {
                boolean needRemove = false;
                for (String fixName : fixedNames) {
                    if (fixName.equals(route.getStartNode()) || fixName.equals(route.getEndNode())) {
                        needRemove = true;
                        break;
                    }
                }
                if (!needRemove) {
                    filteredRoutes.add(route);
                }
            }
            int removedCount = existingRoutes.size() - filteredRoutes.size();
            System.out.println("已删除 " + removedCount + " 条旧距离数据");

            // 6. 获取需要修复节点到其他节点的新距离
            List<RouteDistance> newRoutes = new ArrayList<>();
            int success = 0, fail = 0;

            for (String fixName : fixedNames) {
                Node fixNode = null;
                for (Node node : nodes) {
                    if (fixName.equals(node.name)) {
                        fixNode = node;
                        break;
                    }
                }

                if (fixNode == null) continue;

                double[] newCoords = FIX_NODES.get(fixName);
                System.out.println("\n开始获取 " + fixName + " 到其他节点的距离...");

                for (int i = 0; i < nodes.size(); i++) {
                    Node other = nodes.get(i);
                    if (fixName.equals(other.name)) continue;

                    System.out.printf("[%d/%d] %s -> %s ... ", i + 1, nodes.size(), fixName, other.name);

                    Thread.sleep(REQUEST_INTERVAL);

                    try {
                        double distance = fixer.getDrivingDistance(fixNode.lng, fixNode.lat, other.lng, other.lat);
                        if (distance > 0) {
                            RouteDistance route = new RouteDistance(
                                    fixName, newCoords[0], newCoords[1],
                                    other.name, other.lng, other.lat, distance);
                            newRoutes.add(route);
                            success++;
                            System.out.printf("%.1f km%n", distance);
                        } else {
                            fail++;
                            System.out.println("失败");
                        }
                    } catch (Exception e) {
                        fail++;
                        System.out.println("异常: " + e.getMessage());
                    }
                }
            }

            // 7. 合并新旧数据
            filteredRoutes.addAll(newRoutes);
            System.out.println("\n获取完成: 成功 " + success + ", 失败 " + fail);

            // 8. 保存修复后的距离文件
            fixer.saveRoutesToCsv(filteredRoutes, "达州市乡镇道路距离.csv");
            fixer.saveRoutesToSql(filteredRoutes, "达州市乡镇道路距离.sql");
            System.out.println("距离文件已更新");

            // 9. 更新进度文件
            fixer.updateProgressFile(filteredRoutes);

            System.out.println("\n========== 修复完成 ==========");
            System.out.println("共 " + filteredRoutes.size() + " 条距离记录");

        } catch (Exception e) {
            System.err.println("修复失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 从CSV读取节点
     */
    private List<Node> readNodesFromCsv(String filename) throws IOException {
        List<Node> nodes = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line = reader.readLine(); // 跳过标题
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 6) {
                    nodes.add(new Node(
                            parts[0], // name
                            Double.parseDouble(parts[1]), // lng
                            Double.parseDouble(parts[2]), // lat
                            parts[3], // district
                            parts[4], // adCode
                            parts[5]  // level
                    ));
                }
            }
        }
        return nodes;
    }

    /**
     * 保存节点到CSV
     */
    private void saveNodesToCsv(List<Node> nodes, String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("名称,经度,纬度,所属区县,行政区划代码,级别");
            for (Node node : nodes) {
                writer.printf("%s,%.6f,%.6f,%s,%s,%s%n",
                        node.name, node.lng, node.lat, node.district, node.adCode, node.level);
            }
        }
    }

    /**
     * 保存节点到SQL
     */
    private void saveNodesToSql(List<Node> nodes, String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("-- 达州市乡镇坐标数据");
            writer.println("-- 修复时间: " + java.time.LocalDateTime.now());
            writer.println();

            // 仓库节点
            writer.println("-- 仓库节点（达州市烟草专卖局）");
            for (Node node : nodes) {
                if ("warehouse".equals(node.level)) {
                    writer.printf("INSERT INTO vrp_node (node_code, name, x_coordinate, y_coordinate, node_type, demand, status) VALUES ('DEPOT001', '%s', %.6f, %.6f, 2, 0, 1);%n",
                            node.name, node.lng, node.lat);
                    break;
                }
            }
            writer.println();

            // 配送点
            writer.println("-- 配送点（各乡镇）");
            writer.println("INSERT INTO vrp_node (node_code, name, x_coordinate, y_coordinate, node_type, demand, status) VALUES");

            List<Node> deliveryNodes = new ArrayList<>();
            for (Node node : nodes) {
                if (!"warehouse".equals(node.level)) {
                    deliveryNodes.add(node);
                }
            }

            Random random = new Random();
            for (int i = 0; i < deliveryNodes.size(); i++) {
                Node node = deliveryNodes.get(i);
                String comma = (i < deliveryNodes.size() - 1) ? "," : ";";
                String nodeCode = String.format("NODE%03d", i + 1);
                int demand = 5 + random.nextInt(16); // 随机需求5-20
                writer.printf("('%s', '%s', %.6f, %.6f, 1, %d, 1)%s%n",
                        nodeCode, node.name, node.lng, node.lat, demand, comma);
            }
        }
    }

    /**
     * 从CSV读取距离数据
     */
    private List<RouteDistance> readRoutesFromCsv(String filename) throws IOException {
        List<RouteDistance> routes = new ArrayList<>();
        File file = new File(filename);
        if (!file.exists()) return routes;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine(); // 跳过标题
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 7) {
                    routes.add(new RouteDistance(
                            parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                            parts[3], Double.parseDouble(parts[4]), Double.parseDouble(parts[5]),
                            Double.parseDouble(parts[6])));
                }
            }
        }
        return routes;
    }

    /**
     * 保存距离到CSV
     */
    private void saveRoutesToCsv(List<RouteDistance> routes, String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("起始乡镇,起始经度,起始纬度,终止乡镇,终止经度,终止纬度,距离(km)");
            for (RouteDistance r : routes) {
                writer.printf("%s,%.6f,%.6f,%s,%.6f,%.6f,%.2f%n",
                        r.getStartNode(), r.getStartLng(), r.getStartLat(),
                        r.getEndNode(), r.getEndLng(), r.getEndLat(), r.getDistanceKm());
            }
        }
    }

    /**
     * 保存距离到SQL
     */
    private void saveRoutesToSql(List<RouteDistance> routes, String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("-- 达州市乡镇间道路距离数据");
            writer.println("-- 修复时间: " + java.time.LocalDateTime.now());
            writer.println("-- 记录数: " + routes.size());
            writer.println();

            for (RouteDistance r : routes) {
                writer.printf(
                        "INSERT INTO dazhou_delivery_routes (start_node, start_lng, start_lat, end_node, end_lng, end_lat, distance_km) VALUES ('%s', %.6f, %.6f, '%s', %.6f, %.6f, %.2f);%n",
                        r.getStartNode(), r.getStartLng(), r.getStartLat(),
                        r.getEndNode(), r.getEndLng(), r.getEndLat(), r.getDistanceKm());
            }
        }
    }

    /**
     * 调用高德V5驾车路线规划API获取距离
     */
    private double getDrivingDistance(double fromLng, double fromLat, double toLng, double toLat)
            throws IOException, InterruptedException {

        String url = String.format("%s?key=%s&origin=%.6f,%.6f&destination=%.6f,%.6f&strategy=0",
                GaodeConfig.DRIVING_URL,
                GaodeConfig.API_KEY,
                fromLng, fromLat,
                toLng, toLat);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        if ("1".equals(root.path("status").asText())) {
            JsonNode route = root.path("route");
            JsonNode paths = route.path("paths");
            if (paths.isArray() && paths.size() > 0) {
                String distanceStr = paths.get(0).path("distance").asText();
                double distanceMeters = Double.parseDouble(distanceStr);
                return Math.round(distanceMeters / 1000.0 * 100.0) / 100.0;
            }
        }

        System.err.print("错误: " + root.path("info").asText() + " ");
        return -1;
    }

    /**
     * 更新进度文件
     */
    private void updateProgressFile(List<RouteDistance> routes) {
        Set<String> pairs = new HashSet<>();
        for (RouteDistance route : routes) {
            String key1 = route.getStartNode() + "|" + route.getEndNode();
            String key2 = route.getEndNode() + "|" + route.getStartNode();
            pairs.add(key1);
            pairs.add(key2);
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter("distance_progress.dat"))) {
            for (String pair : pairs) {
                writer.println(pair);
            }
        } catch (IOException e) {
            System.err.println("更新进度文件失败: " + e.getMessage());
        }
    }
}
