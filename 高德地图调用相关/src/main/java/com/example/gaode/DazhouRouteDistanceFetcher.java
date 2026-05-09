package com.example.gaode;

import com.example.gaode.model.RouteDistance;
import com.example.gaode.model.TownshipInfo;
import com.example.gaode.service.GaodeService;
import com.example.gaode.service.RouteDistanceService;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * 达州市乡镇间道路距离获取工具
 * 运行main方法即可获取乡镇间的实际驾车距离
 */
public class DazhouRouteDistanceFetcher {

    public static void main(String[] args) {
        System.out.println("========== 达州市乡镇间道路距离获取工具 ==========\n");

        GaodeService gaodeService = new GaodeService();
        RouteDistanceService routeService = new RouteDistanceService();

        try {
            // 0. 获取仓库坐标（达州市烟草专卖局）
            System.out.println("正在获取仓库坐标...");
            TownshipInfo depot = gaodeService.geocode("达州市烟草专卖局");
            if (depot == null) {
                System.err.println("无法获取仓库坐标，使用默认坐标");
                depot = new TownshipInfo("达州市烟草专卖局", "", "达州市", 104.633667, 31.128444, "warehouse");
            }

            // 1. 获取所有乡镇坐标
            List<TownshipInfo> townships = gaodeService.getDazhouTownships();

            // 2. 把仓库加入乡镇列表，一起计算道路距离
            townships.add(depot);
            System.out.println("已添加仓库节点: " + depot.getName());

            // 3. 找出相邻乡镇对并获取实际道路距离（包含仓库）
            List<RouteDistance> routes = routeService.fetchAllDistances(townships);

            // 3. 保存到文件
            saveToCsv(routes, "达州市乡镇道路距离.csv");
            saveToSql(routes, "达州市乡镇道路距离.sql");

            System.out.println("\n========== 完成 ==========");
            System.out.println("共获取 " + routes.size() + " 条道路距离");
            System.out.println("文件已保存:");
            System.out.println("  - 达州市乡镇道路距离.csv");
            System.out.println("  - 达州市乡镇道路距离.sql");

        } catch (Exception e) {
            System.err.println("获取道路距离失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 保存为CSV文件
     */
    private static void saveToCsv(List<RouteDistance> routes, String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("起始乡镇,起始经度,起始纬度,终止乡镇,终止经度,终止纬度,距离(km)");
            for (RouteDistance r : routes) {
                writer.printf("%s,%.6f,%.6f,%s,%.6f,%.6f,%.2f%n",
                        r.getStartNode(), r.getStartLng(), r.getStartLat(),
                        r.getEndNode(), r.getEndLng(), r.getEndLat(), r.getDistanceKm());
            }
        }
        System.out.println("CSV文件保存成功: " + filename);
    }

    /**
     * 保存为SQL文件（可直接导入dazhou_delivery_routes表）
     */
    private static void saveToSql(List<RouteDistance> routes, String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("-- 达州市乡镇间道路距离数据");
            writer.println("-- 生成时间: " + java.time.LocalDateTime.now());
            writer.println();

            for (RouteDistance r : routes) {
                writer.printf(
                    "INSERT INTO dazhou_delivery_routes (start_node, start_lng, start_lat, end_node, end_lng, end_lat, distance_km) VALUES ('%s', %.6f, %.6f, '%s', %.6f, %.6f, %.2f);%n",
                    r.getStartNode(), r.getStartLng(), r.getStartLat(),
                    r.getEndNode(), r.getEndLng(), r.getEndLat(), r.getDistanceKm());
            }
        }
        System.out.println("SQL文件保存成功: " + filename);
    }
}
