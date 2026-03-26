package com.example.vrpdemo.dto;

import lombok.Data;

import java.util.List;

/**
 * 车辆路线DTO
 * 用于返回单辆车的完整配送路线信息
 */
@Data
public class VehicleRouteDTO {

    /** 车辆ID */
    private Long vehicleId;

    /** 车辆编码 */
    private String vehicleCode;

    /** 该路线总距离 */
    private Double totalDistance;

    /** 配送节点数量 */
    private Integer nodeCount;

    /** 
     * 路线节点列表
     * 按访问顺序排列，包含坐标信息
     */
    private List<RouteNodeDTO> nodes;

    /**
     * 计算总距离
     */
    public void calculateTotalDistance() {
        if (nodes == null || nodes.isEmpty()) {
            this.totalDistance = 0.0;
            this.nodeCount = 0;
            return;
        }
        this.nodeCount = nodes.size();
    }
}
