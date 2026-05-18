package com.example.vrpdemo.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务结果响应DTO
 * 用于返回VRP计算任务的完整结果
 */
@Data
public class TaskResultResponse {

    /** 任务ID */
    private Long taskId;

    /** 任务名称 */
    private String taskName;

    /** 使用车辆数量 */
    private Integer vehicleCount;

    /** 
     * 任务状态
     * 0-待计算 1-计算中 2-完成 3-失败
     */
    private Integer status;

    /** 状态描述 */
    private String statusDesc;

    /** 总里程 */
    private Double totalDistance;

    /** 里程差距比 (最大里程-最小里程)/平均里程 */
    private Double gapRatio;

    /** 节点数差距比 (最大节点数-最小节点数)/平均节点数 */
    private Double nodeGapRatio;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 完成时间 */
    private LocalDateTime completeTime;

    /** 各车辆路线详情 */
    private List<VehicleRouteDTO> routes;

    /**
     * 获取状态描述
     */
    public String getStatusDesc() {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "待计算";
            case 1 -> "计算中";
            case 2 -> "完成";
            case 3 -> "失败";
            default -> "未知";
        };
    }
}
