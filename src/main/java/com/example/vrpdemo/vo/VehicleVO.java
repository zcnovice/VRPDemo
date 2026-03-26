package com.example.vrpdemo.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 车辆VO（View Object）
 * 算法层使用的车辆数据模型
 * 包含车辆的配送路线和载重信息
 */
@Data
public class VehicleVO {

    /** 车辆ID（对应数据库主键） */
    private Long id;

    /** 车辆编码 */
    private String vehicleCode;

    /** 最大载重 */
    private double capacity;

    /** 
     * 配送路线（节点访问顺序）
     * 不包含起点仓库和终点仓库，计算时动态添加
     */
    private List<NodeVO> route;

    /** 总行驶距离 */
    private double totalDistance;

    /** 当前载重（已分配货物量） */
    private double currentLoad;

    /** 
     * 扇形区域信息
     * [主区域起始角度, 主区域结束角度, 扩展区起始角度, 扩展区结束角度]
     * 用于约束车辆的服务范围
     */
    private double[] sector;

    /** 默认构造方法 */
    public VehicleVO() {
        this.route = new ArrayList<>();
        this.totalDistance = 0.0;
        this.currentLoad = 0.0;
    }

    /**
     * 带参构造方法
     * 
     * @param id 车辆ID
     * @param vehicleCode 车辆编码
     * @param capacity 最大载重
     */
    public VehicleVO(Long id, String vehicleCode, double capacity) {
        this();
        this.id = id;
        this.vehicleCode = vehicleCode;
        this.capacity = capacity;
    }

    /**
     * 添加节点到路线末尾
     * 同时更新当前载重
     * 
     * @param node 要添加的节点
     */
    public void addNode(NodeVO node) {
        route.add(node);
        currentLoad += node.getDemand();
    }

    /**
     * 在指定位置插入节点
     * 
     * @param index 插入位置
     * @param node 要插入的节点
     */
    public void insertNode(int index, NodeVO node) {
        route.add(index, node);
        currentLoad += node.getDemand();
    }

    /**
     * 移除指定位置的节点
     * 同时减少当前载重
     * 
     * @param index 要移除的节点位置
     * @return 被移除的节点
     */
    public NodeVO removeNode(int index) {
        NodeVO node = route.remove(index);
        currentLoad -= node.getDemand();
        return node;
    }

    /**
     * 获取路线节点数量
     * 
     * @return 路线中节点的数量
     */
    public int getNodeCount() {
        return route.size();
    }

    /**
     * 计算车辆的总行驶距离
     * 路线：仓库 -> 所有节点 -> 仓库
     * 
     * @param depot 仓库节点（起点和终点）
     * @return 总行驶距离
     */
    public double calculateDistance(NodeVO depot) {
        if (route.isEmpty()) {
            return 0.0;
        }

        double totalDist = 0.0;
        
        // 1. 从仓库到第一个节点
        totalDist += depot.distanceTo(route.get(0));

        // 2. 中间各节点之间的距离
        for (int i = 0; i < route.size() - 1; i++) {
            totalDist += route.get(i).distanceTo(route.get(i + 1));
        }

        // 3. 最后一个节点返回仓库
        totalDist += route.get(route.size() - 1).distanceTo(depot);

        this.totalDistance = totalDist;
        return totalDist;
    }

    /**
     * 深拷贝车辆对象
     * 用于生成邻域解时复制车辆状态
     * 
     * @return 车辆对象的深拷贝
     */
    public VehicleVO deepCopy() {
        VehicleVO copy = new VehicleVO();
        copy.id = this.id;
        copy.vehicleCode = this.vehicleCode;
        copy.capacity = this.capacity;
        copy.totalDistance = this.totalDistance;
        copy.currentLoad = this.currentLoad;
        
        // 复制路线（节点对象本身不需要深拷贝，因为坐标不变）
        for (NodeVO node : this.route) {
            copy.route.add(node);
        }
        
        // 复制扇形区域
        if (this.sector != null) {
            copy.sector = this.sector.clone();
        }
        
        return copy;
    }

    /**
     * 复制路线列表
     * 
     * @return 路线列表的副本
     */
    public List<NodeVO> copyRoute() {
        return new ArrayList<>(route);
    }
}
