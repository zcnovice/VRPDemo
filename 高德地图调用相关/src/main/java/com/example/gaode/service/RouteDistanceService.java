package com.example.gaode.service;

import com.example.gaode.config.GaodeConfig;
import com.example.gaode.model.RouteDistance;
import com.example.gaode.model.TownshipInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * 道路距离服务
 * 只获取相邻乡镇间的道路距离
 */
public class RouteDistanceService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RouteDistanceService() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 计算两点之间的直线距离（公里，近似）
     */
    private double calcStraightDistance(TownshipInfo a, TownshipInfo b) {
        double dx = (a.getLongitude() - b.getLongitude()) * 85;
        double dy = (a.getLatitude() - b.getLatitude()) * 111;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 找出相邻乡镇对
     * 策略：用最小生成树思路，确保所有节点连通，边数最少
     * 仓库节点特殊处理：强制与最近的3-5个乡镇连接
     *
     * @param townships 所有乡镇（包含仓库）
     * @return 相邻乡镇对（不重复）
     */
    public List<RouteDistance> findNeighborPairs(List<TownshipInfo> townships) {
        // 1. 生成所有可能的乡镇对，按距离排序
        List<double[]> allPairs = new ArrayList<>(); // [indexA, indexB, distance]
        for (int i = 0; i < townships.size(); i++) {
            for (int j = i + 1; j < townships.size(); j++) {
                double dist = calcStraightDistance(townships.get(i), townships.get(j));
                allPairs.add(new double[]{i, j, dist});
            }
        }
        allPairs.sort(Comparator.comparingDouble(p -> p[2]));

        // 2. 并查集：确保连通且无环
        int n = townships.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        List<RouteDistance> result = new ArrayList<>();
        Set<String> addedPairs = new HashSet<>();

        // 3. 先处理仓库节点：强制与最近的3-5个乡镇连接
        int depotIndex = -1;
        for (int i = 0; i < townships.size(); i++) {
            if ("warehouse".equals(townships.get(i).getLevel())) {
                depotIndex = i;
                break;
            }
        }

        if (depotIndex >= 0) {
            // 找仓库到所有乡镇的距离，按距离排序
            List<double[]> depotPairs = new ArrayList<>();
            for (int j = 0; j < townships.size(); j++) {
                if (j == depotIndex) continue;
                double dist = calcStraightDistance(townships.get(depotIndex), townships.get(j));
                depotPairs.add(new double[]{depotIndex, j, dist});
            }
            depotPairs.sort(Comparator.comparingDouble(p -> p[2]));

            // 仓库与最近的3-5个乡镇连接
            int connectCount = Math.min(5, depotPairs.size());
            for (int i = 0; i < connectCount; i++) {
                double[] pair = depotPairs.get(i);
                int a = (int) pair[0];
                int b = (int) pair[1];

                TownshipInfo ta = townships.get(a);
                TownshipInfo tb = townships.get(b);
                String key = ta.getName() + "|" + tb.getName();

                if (!addedPairs.contains(key)) {
                    addedPairs.add(key);
                    result.add(new RouteDistance(
                            ta.getName(), ta.getLongitude(), ta.getLatitude(),
                            tb.getName(), tb.getLongitude(), tb.getLatitude(), 0));
                    // 合并到同一个连通分量
                    parent[find(parent, a)] = find(parent, b);
                    System.out.println("仓库连接: " + ta.getName() + " -> " + tb.getName());
                }
            }
        }

        // 4. 处理其他乡镇对（50公里限制）
        for (double[] pair : allPairs) {
            int a = (int) pair[0];
            int b = (int) pair[1];
            double dist = pair[2];

            // 跳过太远的（超过50公里不算相邻）
            if (dist > 50) continue;

            // 并查集：如果已经在同一个连通分量，跳过（避免环）
            if (find(parent, a) == find(parent, b)) continue;

            // 合并
            parent[find(parent, a)] = find(parent, b);

            // 添加到结果
            TownshipInfo ta = townships.get(a);
            TownshipInfo tb = townships.get(b);
            String key = ta.getName() + "|" + tb.getName();
            if (!addedPairs.contains(key)) {
                addedPairs.add(key);
                result.add(new RouteDistance(
                        ta.getName(), ta.getLongitude(), ta.getLatitude(),
                        tb.getName(), tb.getLongitude(), tb.getLatitude(), 0));
            }

            // 如果已经有n-1条边，说明已经连通所有节点
            if (result.size() >= n - 1) break;
        }

        return result;
    }

    /**
     * 并查集查找
     */
    private int find(int[] parent, int x) {
        if (parent[x] != x) parent[x] = find(parent, parent[x]);
        return parent[x];
    }

    /**
     * 调用高德驾车路线规划API获取实际距离
     */
    public double getDrivingDistance(double fromLng, double fromLat, double toLng, double toLat)
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

        System.err.println("路线规划失败: " + root.path("info").asText());
        return -1;
    }

    /**
     * 批量获取所有相邻乡镇对的道路距离
     */
    public List<RouteDistance> fetchAllDistances(List<TownshipInfo> townships)
            throws IOException, InterruptedException {

        // 1. 找出所有相邻乡镇对
        List<RouteDistance> pairs = findNeighborPairs(townships);
        System.out.println("找到 " + pairs.size() + " 对相邻乡镇");

        // 2. 逐个调用API获取实际距离
        List<RouteDistance> results = new ArrayList<>();
        int success = 0, fail = 0;

        for (int i = 0; i < pairs.size(); i++) {
            RouteDistance pair = pairs.get(i);
            System.out.printf("正在查询 [%d/%d]: %s -> %s ...",
                    i + 1, pairs.size(), pair.getStartNode(), pair.getEndNode());

            Thread.sleep(GaodeConfig.REQUEST_INTERVAL);

            try {
                double distance = getDrivingDistance(
                        pair.getStartLng(), pair.getStartLat(),
                        pair.getEndLng(), pair.getEndLat());

                if (distance > 0) {
                    pair.setDistanceKm(distance);
                    results.add(pair);
                    success++;
                    System.out.printf(" %.1f km%n", distance);
                } else {
                    fail++;
                    System.out.println(" 失败");
                }
            } catch (Exception e) {
                fail++;
                System.out.println(" 异常: " + e.getMessage());
            }
        }

        System.out.println("\n查询完成: 成功 " + success + " 条, 失败 " + fail + " 条");
        return results;
    }
}
