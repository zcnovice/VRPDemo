package com.example.vrpdemo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建任务请求DTO
 * 用于接收创建VRP计算任务的请求参数
 */
@Data
public class TaskCreateRequest {

    /** 任务名称 */
    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    /** 使用车辆数量 */
    @NotNull(message = "车辆数量不能为空")
    @Min(value = 1, message = "车辆数量至少为1")
    private Integer vehicleCount;
}
