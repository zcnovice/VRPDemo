package com.example.vrpdemo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 节点VO（View Object）
 * 算法层使用的节点数据模型
 * 包含算法计算所需的坐标和角度信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeVO {

    /** 节点ID（对应数据库主键） */
    private Long id;

    /** 节点编码 */
    private String nodeCode;

    /** 节点名称 */
    private String name;

    /** X坐标 */
    private double x;

    /** Y坐标 */
    private double y;

    /** 需求量 */
    private double demand;

    /** 
     * 相对于仓库的角度（0-360度）
     * 用于扇形区域分配算法
     */
    private double angle;

    /**
     * 计算到另一个节点的欧几里得距离
     * 使用勾股定理计算直线距离
     * 
     * @param other 目标节点
     * @return 两点之间的直线距离
     */
    public double distanceTo(NodeVO other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 计算相对于仓库节点/原点的角度
     * 角度范围：0-360度
     * 用于将节点分配到扇形区域
     * 
     * @param depotX 仓库X坐标
     * @param depotY 仓库Y坐标
     * @return 角度值（0-360度）
     */
    public double calculateAngle(double depotX, double depotY) {
        // 计算相对于仓库的坐标差
        double dx = this.x - depotX;
        double dy = this.y - depotY;
        
        // 使用atan2计算角度（弧度），然后转换为度
        this.angle = Math.toDegrees(Math.atan2(dy, dx));
        
        // 将角度转换到0-360范围
        if (this.angle < 0) {
            this.angle += 360;
        }
        
        return this.angle;
    }

    /**
     * 简化构造方法（用于测试）
     */
    public NodeVO(Long id, double x, double y) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.demand = 0;
    }
}
