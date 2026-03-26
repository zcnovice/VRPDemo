package com.example.vrpdemo.service;

import com.example.vrpdemo.dto.NodeDataRequest;
import com.example.vrpdemo.dto.TaskCreateRequest;
import com.example.vrpdemo.dto.TaskResultResponse;

import java.util.List;

/**
 * VRP业务服务接口
 * 提供VRP求解的核心业务功能
 */
public interface VrpService {

    /**
     * 创建并执行VRP计算任务
     * 
     * @param request 任务创建请求
     * @return 任务ID
     */
    Long createAndExecuteTask(TaskCreateRequest request);

    /**
     * 创建VRP计算任务（不立即执行）
     * 
     * @param request 任务创建请求
     * @return 任务ID
     */
    Long createTask(TaskCreateRequest request);

    /**
     * 执行指定的VRP计算任务
     * 
     * @param taskId 任务ID
     */
    void executeTask(Long taskId);

    /**
     * 获取任务结果
     * 
     * @param taskId 任务ID
     * @return 任务结果详情
     */
    TaskResultResponse getTaskResult(Long taskId);

    /**
     * 获取所有任务列表
     * 
     * @return 任务列表
     */
    List<TaskResultResponse> getAllTasks();

    /**
     * 生成随机测试数据
     * 
     * @param request 生成请求
     * @return 生成的节点数量
     */
    int generateTestData(NodeDataRequest request);

    /**
     * 初始化车辆数据
     * 生成指定数量的测试车辆
     * 
     * @param count 车辆数量
     * @return 生成的车辆数量
     */
    int initVehicles(int count);
}
