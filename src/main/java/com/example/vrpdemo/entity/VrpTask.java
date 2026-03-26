package com.example.vrpdemo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * VRP计算任务实体 - 对应 vrp_task 表
 * 用于记录每次VRP求解任务的信息和结果
 */
@Data
@TableName("vrp_task")
public class VrpTask {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务名称 */
    private String taskName;

    /** 使用车辆数量 */
    private Integer vehicleCount;

    /** 计算得到的总里程 */
    private Double totalDistance;

    /** 
     * 任务状态
     * 0 - 待计算
     * 1 - 计算中
     * 2 - 完成
     * 3 - 失败
     */
    private Integer status;

    /** 计算结果JSON（存储完整的路线方案） */
    private String resultJson;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 完成时间 */
    private LocalDateTime completeTime;
}
