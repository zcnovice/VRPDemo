package com.example.vrpdemo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点实体 - 对应 vrp_node 表
 * 用于存储配送点和仓库信息
 */
@Data
@TableName("vrp_node")
public class VrpNode {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 节点编码（唯一标识） */
    private String nodeCode;

    /** 节点名称 */
    private String name;

    /** X坐标 */
    private Double xCoordinate;

    /** Y坐标 */
    private Double yCoordinate;

    /** 
     * 节点类型
     * 1 - 配送点（客户位置）
     * 2 - 仓库（配送中心/depot）
     */
    private Integer nodeType;

    /** 需求量（配送点需要配送的货物量） */
    private Double demand;

    /** 
     * 状态
     * 0 - 禁用
     * 1 - 启用
     */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
