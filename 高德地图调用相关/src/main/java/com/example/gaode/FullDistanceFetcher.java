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
 * 全量道路距离获取工具
 * 从SQL文件读取节点坐标，调用高德V5 API获取所有节点对之间的道路距离
 * 支持断点续传：已获取的距离会保存到进度文件，下次运行自动跳过
 */
public class FullDistanceFetcher {

    private static final String INPUT_FILE = "达州市乡镇坐标.sql";
    private static final String OUTPUT_SQL = "达州市乡镇道路距离.sql";
    private static final String OUTPUT_CSV = "达州市乡镇道路距离.csv";
    private static final String PROGRESS_FILE = "distance_progress.dat";

    // 请求间隔：每秒3次 = 333ms
    private static final long REQUEST_INTERVAL = 334;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FullDistanceFetcher() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 节点信息
     */
    static class Node {
        String code;
        String name;
        double lng;
        double lat;

        Node(String code, String name, double lng, double lat) {
            this.code = code;
            this.name = name;
            this.lng = lng;
            this.lat = lat;
        }
    }

    public static void main(String[] args) {
        System.out.println("========== 达州市全量道路距离获取工具 ==========");
        System.out.println("API版本: V5");
        System.out.println("请求限制: 每秒3次");
        System.out.println();

        FullDistanceFetcher fetcher = new FullDistanceFetcher();

        try {
            // 1. 解析SQL文件获取所有节点
            List<Node> nodes = fetcher.parseNodesFromSql(INPUT_FILE);
            System.out.println("从SQL文件读取到 " + nodes.size() + " 个节点");

            // 2. 加载已完成的进度
            Set<String> completedPairs = fetcher.loadProgress();
            System.out.println("已完成 " + completedPairs.size() + " 对距离查询");

            // 3. 生成所有需要查询的节点对
            List<String[]> pendingPairs = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                for (int j = i + 1; j < nodes.size(); j++) {
                    Node a = nodes.get(i);
                    Node b = nodes.get(j);
                    String pairKey = a.name + "|" + b.name;
                    if (!completedPairs.contains(pairKey)) {
                        pendingPairs.add(new String[]{a.name, String.valueOf(a.lng), String.valueOf(a.lat),
                                b.name, String.valueOf(b.lng), String.valueOf(b.lat)});
                    }
                }
            }

            int totalPairs = nodes.size() * (nodes.size() - 1) / 2;
            System.out.println("总计需要查询: " + totalPairs + " 对");
            System.out.println("待查询: " + pendingPairs.size() + " 对");
            System.out.println();

            if (pendingPairs.isEmpty()) {
                System.out.println("所有距离已获取完成！");
                // 导出最终结果
                fetcher.exportResults(nodes, completedPairs);
                return;
            }

            // 4. 逐个获取距离
            List<RouteDistance> allRoutes = fetcher.loadExistingResults();
            int success = 0, fail = 0;
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < pendingPairs.size(); i++) {
                String[] pair = pendingPairs.get(i);
                String nameA = pair[0];
                double lngA = Double.parseDouble(pair[1]);
                double latA = Double.parseDouble(pair[2]);
                String nameB = pair[3];
                double lngB = Double.parseDouble(pair[4]);
                double latB = Double.parseDouble(pair[5]);

                // 计算进度
                int done = completedPairs.size() + i + 1;
                double percent = (double) done / totalPairs * 100;

                // 估算剩余时间
                long elapsed = System.currentTimeMillis() - startTime;
                long eta = 0;
                if (i > 0) {
                    long avgTime = elapsed / i;
                    eta = avgTime * (pendingPairs.size() - i) / 1000;
                }

                System.out.printf("[%d/%d %.1f%%] %s -> %s ... ",
                        done, totalPairs, percent, nameA, nameB);

                Thread.sleep(REQUEST_INTERVAL);

                try {
                    double distance = fetcher.getDrivingDistance(lngA, latA, lngB, latB);
                    if (distance > 0) {
                        RouteDistance route = new RouteDistance(nameA, lngA, latA, nameB, lngB, latB, distance);
                        allRoutes.add(route);
                        completedPairs.add(nameA + "|" + nameB);

                        // 保存进度
                        fetcher.saveProgress(completedPairs);
                        fetcher.appendResult(route);

                        success++;
                        System.out.printf("%.1f km (剩余约 %d 分钟)%n", distance, eta / 60);
                    } else {
                        fail++;
                        System.out.println("失败");
                    }
                } catch (Exception e) {
                    fail++;
                    System.out.println("异常: " + e.getMessage());
                }
            }

            long totalTime = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println("\n========== 完成 ==========");
            System.out.printf("成功: %d, 失败: %d, 耗时: %d分%d秒%n",
                    success, fail, totalTime / 60, totalTime % 60);

        } catch (Exception e) {
            System.err.println("获取道路距离失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 解析SQL文件获取节点坐标
     */
    private List<Node> parseNodesFromSql(String filename) throws IOException {
        List<Node> nodes = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\('([^']+)',\\s*'([^']+)',\\s*([\\d.]+),\\s*([\\d.]+)");

        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    String code = matcher.group(1);
                    String name = matcher.group(2);
                    double lng = Double.parseDouble(matcher.group(3));
                    double lat = Double.parseDouble(matcher.group(4));
                    nodes.add(new Node(code, name, lng, lat));
                }
            }
        }

        return nodes;
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
     * 加载已完成的进度
     */
    private Set<String> loadProgress() {
        Set<String> completed = new HashSet<>();
        File file = new File(PROGRESS_FILE);
        if (!file.exists()) return completed;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                completed.add(line.trim());
            }
        } catch (IOException e) {
            System.err.println("加载进度文件失败: " + e.getMessage());
        }
        return completed;
    }

    /**
     * 保存进度
     */
    private void saveProgress(Set<String> completed) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(PROGRESS_FILE))) {
            for (String pair : completed) {
                writer.println(pair);
            }
        } catch (IOException e) {
            System.err.println("保存进度文件失败: " + e.getMessage());
        }
    }

    /**
     * 追加一条结果到文件
     */
    private void appendResult(RouteDistance route) throws IOException {
        // 追加到CSV
        File csvFile = new File(OUTPUT_CSV);
        boolean isNew = !csvFile.exists();
        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile, true))) {
            if (isNew) {
                writer.println("起始乡镇,起始经度,起始纬度,终止乡镇,终止经度,终止纬度,距离(km)");
            }
            writer.printf("%s,%.6f,%.6f,%s,%.6f,%.6f,%.2f%n",
                    route.getStartNode(), route.getStartLng(), route.getStartLat(),
                    route.getEndNode(), route.getEndLng(), route.getEndLat(), route.getDistanceKm());
        }

        // 追加到SQL
        File sqlFile = new File(OUTPUT_SQL);
        isNew = !sqlFile.exists();
        try (PrintWriter writer = new PrintWriter(new FileWriter(sqlFile, true))) {
            if (isNew) {
                writer.println("-- 达州市乡镇间道路距离数据");
                writer.println("-- 生成时间: " + java.time.LocalDateTime.now());
                writer.println();
            }
            writer.printf(
                    "INSERT INTO dazhou_delivery_routes (start_node, start_lng, start_lat, end_node, end_lng, end_lat, distance_km) VALUES ('%s', %.6f, %.6f, '%s', %.6f, %.6f, %.2f);%n",
                    route.getStartNode(), route.getStartLng(), route.getStartLat(),
                    route.getEndNode(), route.getEndLng(), route.getEndLat(), route.getDistanceKm());
        }
    }

    /**
     * 加载已有的结果
     */
    private List<RouteDistance> loadExistingResults() {
        List<RouteDistance> routes = new ArrayList<>();
        File csvFile = new File(OUTPUT_CSV);
        if (!csvFile.exists()) return routes;

        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line = reader.readLine(); // 跳过标题行
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 7) {
                    routes.add(new RouteDistance(
                            parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                            parts[3], Double.parseDouble(parts[4]), Double.parseDouble(parts[5]),
                            Double.parseDouble(parts[6])));
                }
            }
        } catch (IOException e) {
            System.err.println("加载已有结果失败: " + e.getMessage());
        }
        return routes;
    }

    /**
     * 导出最终结果（重新生成完整的SQL和CSV文件）
     */
    private void exportResults(List<Node> nodes, Set<String> completedPairs) throws IOException {
        System.out.println("正在导出最终结果...");

        List<RouteDistance> routes = loadExistingResults();
        System.out.println("共 " + routes.size() + " 条距离记录");

        // 重新生成SQL文件
        try (PrintWriter writer = new PrintWriter(new FileWriter(OUTPUT_SQL))) {
            writer.println("-- 达州市乡镇间道路距离数据（全量）");
            writer.println("-- 节点数: " + nodes.size());
            writer.println("-- 距离记录数: " + routes.size());
            writer.println("-- 生成时间: " + java.time.LocalDateTime.now());
            writer.println();

            for (RouteDistance r : routes) {
                writer.printf(
                        "INSERT INTO dazhou_delivery_routes (start_node, start_lng, start_lat, end_node, end_lng, end_lat, distance_km) VALUES ('%s', %.6f, %.6f, '%s', %.6f, %.6f, %.2f);%n",
                        r.getStartNode(), r.getStartLng(), r.getStartLat(),
                        r.getEndNode(), r.getEndLng(), r.getEndLat(), r.getDistanceKm());
            }
        }

        // 重新生成CSV文件
        try (PrintWriter writer = new PrintWriter(new FileWriter(OUTPUT_CSV))) {
            writer.println("起始乡镇,起始经度,起始纬度,终止乡镇,终止经度,终止纬度,距离(km)");
            for (RouteDistance r : routes) {
                writer.printf("%s,%.6f,%.6f,%s,%.6f,%.6f,%.2f%n",
                        r.getStartNode(), r.getStartLng(), r.getStartLat(),
                        r.getEndNode(), r.getEndLng(), r.getEndLat(), r.getDistanceKm());
            }
        }

        System.out.println("最终结果已导出到:");
        System.out.println("  - " + OUTPUT_SQL);
        System.out.println("  - " + OUTPUT_CSV);
    }
}
