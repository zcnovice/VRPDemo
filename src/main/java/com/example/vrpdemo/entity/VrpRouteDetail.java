package com.example.vrpdemo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 路线明细实体 - 对应 vrp_route_detail 表
 * 用于存储每条配送路线的详细节点序列
 */
@Data
@TableName("vrp_route_detail")
public class VrpRouteDetail {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 执行该路线的车辆ID */
    private Long vehicleId;

    /** 
     * 配送顺序
     * 表示该节点在路线中的访问顺序（从1开始）
     */
    private Integer sequence;

    /** 配送节点ID */
    private Long nodeId;

    /** 距离上一个节点的距离 */
    private Double distanceFromPrev;

    /** 创建时间 */
    private LocalDateTime createTime;
}
