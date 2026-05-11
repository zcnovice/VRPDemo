package com.example.vrpdemo.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解决方案VO（View Object）
 * 表示VRP问题的一个完整解
 * 包含所有车辆的路线和评分信息
 */
@Data
public class SolutionVO {

    /** 车辆ID -> 车辆对象的映射 */
    private Map<Long, VehicleVO> vehicleMap;

    /** 总行驶距离（所有车辆里程之和） */
    private double totalDistance;

    /** 聚类评分（衡量路线的紧凑程度，越小越好） */
    private double clusterScore;

    /** 均衡评分（衡量各车辆负载均衡程度，越小越好） */
    private double balanceScore;

    /** 综合得分（加权计算，越低越好） */
    private double score;

    /** 仓库节点 */
    private NodeVO depot;

    /** 默认构造方法 */
    public SolutionVO() {
        this.vehicleMap = new HashMap<>();
    }

    /**
     * 带仓库节点的构造方法
     * 
     * @param depot 仓库节点
     */
    public SolutionVO(NodeVO depot) {
        this();
        this.depot = depot;
    }

    /**
     * 添加车辆到解决方案
     * 
     * @param vehicle 车辆对象
     */
    public void addVehicle(VehicleVO vehicle) {
        vehicleMap.put(vehicle.getId(), vehicle);
    }

    /**
     * 获取指定车辆
     * 
     * @param vehicleId 车辆ID
     * @return 车辆对象
     */
    public VehicleVO getVehicle(Long vehicleId) {
        return vehicleMap.get(vehicleId);
    }

    /**
     * 获取所有车辆列表
     * 
     * @return 车辆列表
     */
    public List<VehicleVO> getVehicles() {
        return new ArrayList<>(vehicleMap.values());
    }

    /**
     * 获取车辆数量
     * 
     * @return 参与配送的车辆数量
     */
    public int getVehicleCount() {
        return vehicleMap.size();
    }

    /**
     * 计算综合评分
     * 评分函数 = 加权(总里程 + 聚类评分 + 均衡评分)
     * 
     * @param weightDistance 总里程权重
     * @param weightCluster 聚类权重
     * @param weightBalance 均衡权重
     */
    public void calculateScore(double weightDistance, double weightCluster, double weightBalance) {
        // 1. 计算总里程
        calculateTotalDistance();

        // 2. 计算聚类评分
        calculateClusterScore();

        // 3. 计算均衡评分
        calculateBalanceScore();

        // 4. 加权求和
        this.score = weightDistance * totalDistance
                   + weightCluster * clusterScore
                   + weightBalance * balanceScore;
    }

    /**
     * 计算总里程
     */
    private void calculateTotalDistance() {
        this.totalDistance = 0;
        for (VehicleVO vehicle : vehicleMap.values()) {
            this.totalDistance += vehicle.calculateDistance(depot);
        }
    }

    /**
     * 计算聚类评分
     * 方法：计算每辆车路线中各节点到质心的平均距离之和
     * 评分越小表示路线越紧凑
     */
    private void calculateClusterScore() {
        this.clusterScore = 0;

        for (VehicleVO vehicle : vehicleMap.values()) {
            if (vehicle.getNodeCount() == 0) {
                continue;
            }

            // 计算路线质心
            double sumX = 0, sumY = 0;
            for (NodeVO node : vehicle.getRoute()) {
                sumX += node.getX();
                sumY += node.getY();
            }
            double centerX = sumX / vehicle.getNodeCount();
            double centerY = sumY / vehicle.getNodeCount();

            // 计算各节点到质心的平均距离
            double totalDistToCenter = 0;
            for (NodeVO node : vehicle.getRoute()) {
                double dx = node.getX() - centerX;
                double dy = node.getY() - centerY;
                totalDistToCenter += Math.sqrt(dx * dx + dy * dy);
            }

            this.clusterScore += totalDistToCenter / vehicle.getNodeCount();
        }
    }

    /**
     * 计算均衡评分
     * 方法：(变异系数 + 最大最小差距惩罚) × 平均里程
     * 乘以平均里程使评分与总里程同尺度，避免被总里程淹没
     * 目标：各车辆里程差距不超过10%
     */
    private void calculateBalanceScore() {
        if (vehicleMap.isEmpty()) {
            this.balanceScore = 0;
            return;
        }

        // 计算各车辆里程
        List<Double> distances = new ArrayList<>();
        double sum = 0;
        double minDist = Double.MAX_VALUE;
        double maxDist = Double.MIN_VALUE;
        for (VehicleVO vehicle : vehicleMap.values()) {
            double dist = vehicle.calculateDistance(depot);
            distances.add(dist);
            sum += dist;
            minDist = Math.min(minDist, dist);
            maxDist = Math.max(maxDist, dist);
        }

        // 计算平均值
        double avg = sum / distances.size();
        if (avg == 0) {
            this.balanceScore = 0;
            return;
        }

        // 计算标准差
        double variance = 0;
        for (double dist : distances) {
            variance += Math.pow(dist - avg, 2);
        }
        double stdDev = Math.sqrt(variance);

        // 变异系数 = 标准差 / 平均值（归一化，消除规模影响）
        double cv = stdDev / avg;

        // 最大最小差距比 = (最大-最小) / 平均
        double gapRatio = (maxDist - minDist) / avg;

        // 差距超过10%的部分施加惩罚，惩罚系数5.0
        double penalty = 5.0 * Math.max(0, gapRatio - 0.10);

        // 乘以平均里程，使均衡评分与总里程在同一数量级
        this.balanceScore = (cv + penalty) * avg;
    }

    /**
     * 深拷贝解决方案
     * 用于生成邻域解
     * 
     * @return 解决方案的深拷贝
     */
    public SolutionVO deepCopy() {
        SolutionVO copy = new SolutionVO(this.depot);
        copy.totalDistance = this.totalDistance;
        copy.clusterScore = this.clusterScore;
        copy.balanceScore = this.balanceScore;
        copy.score = this.score;

        for (VehicleVO vehicle : this.vehicleMap.values()) {
            copy.addVehicle(vehicle.deepCopy());
        }

        return copy;
    }
}
