package com.example.vrpdemo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 生成测试数据请求DTO
 * 用于生成随机测试节点数据
 */
@Data
public class NodeDataRequest {

    /** 生成节点数量 */
    @NotNull(message = "节点数量不能为空")
    @Min(value = 1, message = "节点数量至少为1")
    private Integer count;

    /** 坐标范围（-range 到 +range） */
    private Double range = 50.0;

    /** 是否清空现有数据 */
    private Boolean clearExisting = true;
}
