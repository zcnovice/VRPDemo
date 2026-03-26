package com.example.vrpdemo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 路线节点DTO
 * 用于返回路线中单个节点的信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteNodeDTO {

    /** 节点ID */
    private Long nodeId;

    /** 节点编码 */
    private String nodeCode;

    /** 节点名称 */
    private String nodeName;

    /** X坐标 */
    private Double x;

    /** Y坐标 */
    private Double y;

    /** 配送顺序（从1开始） */
    private Integer sequence;

    /** 距上一个节点的距离 */
    private Double distanceFromPrev;

    /** 需求量 */
    private Double demand;
}
