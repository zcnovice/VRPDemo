package com.example.vrpdemo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 车辆实体 - 对应 vrp_vehicle 表
 * 用于存储配送车辆信息
 */
@Data
@TableName("vrp_vehicle")
public class VrpVehicle {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 车辆编码（唯一标识） */
    private String vehicleCode;

    /** 最大载重（单位：kg） */
    private Double capacity;

    /** 
     * 状态
     * 0 - 禁用
     * 1 - 启用
     */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;
}
