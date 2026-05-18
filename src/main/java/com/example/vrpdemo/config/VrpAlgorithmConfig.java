package com.example.vrpdemo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * VRP算法配置类
 * 配置模拟退火算法的各项参数
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "vrp.algorithm")
public class VrpAlgorithmConfig {

    // ==================== 退火参数 ====================

    /** 初始温度（越高接受差解的概率越大，探索性越强） */
    private double initialTemperature = 100000.0;

    /** 终止温度（算法停止的温度阈值） */
    private double finalTemperature = 0.01;

    /** 
     * 降温系数（0-1之间）
     * 值越接近1，降温越慢，迭代次数越多，解质量可能更好但耗时更长
     * 推荐值：0.9999
     */
    private double coolingRate = 0.9999;

    // ==================== 权重参数 ====================

    /** 
     * 总里程权重
     * 目标：最小化总行驶距离
     * 推荐值：0.35
     */
    private double weightDistance = 0.15;

    /** 
     * 区域聚类权重
     * 目标：使每辆车的配送区域更集中
     * 推荐值：0.10
     */
    private double weightCluster = 0.05;

    /**
     * 负载均衡权重（变异系数）
     * 目标：使各车辆的里程更加均衡
     * 推荐值：0.55
     */
    private double weightBalance = 0.40;

    /**
     * 极差/平均权重
     * 目标：(最大里程-最小里程)/平均里程，越小越好
     * 推荐值：0.40
     */
    private double weightGapRatio = 0.40;

    // ==================== 日志参数 ====================

    /**
     * 日志打印频率（每N次迭代打印一次）
     * 推荐值：1000
     */
    private int logInterval = 1;

    // ==================== 扇形区域参数 ====================

    /** 
     * 扇形区域重叠比例
     * 允许相邻车辆的服务区域有一定重叠，增加灵活性
     * 推荐值：0.3（30%重叠）
     */
    private double overlapRatio = 0.4;

    // ==================== 参考值（用于归一化） ====================

    /** 总里程参考值 */
    private double refTotalDistance = 4600.0;

    /** 聚类评分参考值 */
    private double refCluster = 2.0;

    /** 均衡评分参考值 */
    private double refBalance = 150.0;
}
