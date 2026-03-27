package com.example.vrpdemo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 达州配送路线实体 - 对应 dazhou_delivery_routes 表
 * 存储两个乡镇之间的道路行驶距离
 */
@Data
@TableName("dazhou_delivery_routes")
public class DazhouDeliveryRoute {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 起始乡镇名称 */
    private String startNode;

    /** 起始经度 */
    private BigDecimal startLng;

    /** 起始纬度 */
    private BigDecimal startLat;

    /** 终止乡镇名称 */
    private String endNode;

    /** 终止经度 */
    private BigDecimal endLng;

    /** 终止纬度 */
    private BigDecimal endLat;

    /** 道路行驶公里数 */
    private BigDecimal distanceKm;
}
