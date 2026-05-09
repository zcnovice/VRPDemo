package com.example.gaode;

import com.example.gaode.model.TownshipInfo;
import com.example.gaode.service.GaodeService;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Random;

/**
 * 达州市乡镇坐标获取工具
 * 运行main方法即可获取达州市所有乡镇的坐标信息
 */
public class DazhouTownshipFetcher {

    public static void main(String[] args) {
        System.out.println("========== 开始获取达州市乡镇坐标 ==========");

        GaodeService gaodeService = new GaodeService();

        try {
            // 0. 获取仓库坐标（达州市烟草专卖局）
            System.out.println("\n正在获取仓库坐标...");
            TownshipInfo depot = gaodeService.geocode("达州市烟草专卖局");
            if (depot == null) {
                System.err.println("无法获取仓库坐标，使用默认坐标");
                depot = new TownshipInfo("达州市烟草专卖局", "", "达州市", 104.633667, 31.128444, "warehouse");
            }

            // 1. 获取所有乡镇坐标
            List<TownshipInfo> townships = gaodeService.getDazhouTownships();

            System.out.println("\n========== 乡镇列表 ==========");
            for (TownshipInfo township : townships) {
                System.out.println(township);
            }

            // 保存到CSV文件
            saveToCsv(townships, depot, "达州市乡镇坐标.csv");

            // 保存到SQL文件
            saveToSql(townships, depot, "达州市乡镇坐标.sql");

            System.out.println("\n========== 完成 ==========");
            System.out.println("共获取 " + townships.size() + " 个乡镇/街道");
            System.out.println("文件已保存:");
            System.out.println("  - 达州市乡镇坐标.csv");
            System.out.println("  - 达州市乡镇坐标.sql");

        } catch (Exception e) {
            System.err.println("获取乡镇信息失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 保存为CSV文件
     */
    private static void saveToCsv(List<TownshipInfo> townships, TownshipInfo depot, String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("名称,经度,纬度,所属区县,行政区划代码,级别");
            // 仓库节点
            writer.printf("%s,%.6f,%.6f,%s,%s,warehouse%n",
                    depot.getName(), depot.getLongitude(), depot.getLatitude(),
                    depot.getDistrict(), depot.getAdCode());
            // 配送点
            for (TownshipInfo t : townships) {
                writer.printf("%s,%.6f,%.6f,%s,%s,%s%n",
                        t.getName(), t.getLongitude(), t.getLatitude(),
                        t.getDistrict(), t.getAdCode(), t.getLevel());
            }
        }
        System.out.println("CSV文件保存成功: " + filename);
    }

    /**
     * 保存为SQL文件（可直接导入vrp_node表）
     */
    private static void saveToSql(List<TownshipInfo> townships, TownshipInfo depot, String filename) throws IOException {
        Random random = new Random();

        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("-- 达州市乡镇坐标数据");
            writer.println("-- 生成时间: " + java.time.LocalDateTime.now());
            writer.println();

            // 仓库节点（达州市烟草专卖局）
            writer.println("-- 仓库节点（" + depot.getName() + "）");
            writer.printf("INSERT INTO vrp_node (node_code, name, x_coordinate, y_coordinate, node_type, demand, status) VALUES ('DEPOT001', '%s', %.6f, %.6f, 2, 0, 1);%n",
                    depot.getName(), depot.getLongitude(), depot.getLatitude());
            writer.println();

            // 配送点
            writer.println("-- 配送点（各乡镇）");
            writer.println("INSERT INTO vrp_node (node_code, name, x_coordinate, y_coordinate, node_type, demand, status) VALUES");

            List<TownshipInfo> filtered = townships.stream()
                    .filter(t -> !"达州市".equals(t.getName()))
                    .toList();

            for (int i = 0; i < filtered.size(); i++) {
                TownshipInfo t = filtered.get(i);
                String comma = (i < filtered.size() - 1) ? "," : ";";
                // 生成节点编码：NODE + 3位序号
                String nodeCode = String.format("NODE%03d", i + 1);
                // 随机需求量：5-20
                int demand = 5 + random.nextInt(16);
                writer.printf("('%s', '%s', %.6f, %.6f, 1, %d, 1)%s%n",
                        nodeCode, t.getName(), t.getLongitude(), t.getLatitude(), demand, comma);
            }
        }
        System.out.println("SQL文件保存成功: " + filename);
    }
}
