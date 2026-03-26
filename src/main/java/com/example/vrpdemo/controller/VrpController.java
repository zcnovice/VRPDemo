package com.example.vrpdemo.controller;

import com.example.vrpdemo.dto.*;
import com.example.vrpdemo.service.VrpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VRP控制器
 * 提供VRP求解相关的REST API接口
 */
@RestController
@RequestMapping("/api/vrp")
@RequiredArgsConstructor
public class VrpController {

    private final VrpService vrpService;

    // ==================== 任务接口 ====================

    /**
     * 创建并执行VRP计算任务
     * 
     * @param request 任务创建请求
     * @return 任务ID
     */
    @PostMapping("/task")
    public ResponseEntity<Map<String, Object>> createAndExecuteTask(
            @Valid @RequestBody TaskCreateRequest request) {
        
        Long taskId = vrpService.createAndExecuteTask(request);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("taskId", taskId);
        result.put("message", "任务创建并执行成功");
        
        return ResponseEntity.ok(result);
    }

    /**
     * 仅创建任务（不执行）
     * 
     * @param request 任务创建请求
     * @return 任务ID
     */
    @PostMapping("/task/create")
    public ResponseEntity<Map<String, Object>> createTask(
            @Valid @RequestBody TaskCreateRequest request) {
        
        Long taskId = vrpService.createTask(request);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("taskId", taskId);
        result.put("message", "任务创建成功");
        
        return ResponseEntity.ok(result);
    }

    /**
     * 执行指定任务
     * 
     * @param taskId 任务ID
     * @return 执行结果
     */
    @PostMapping("/task/{taskId}/execute")
    public ResponseEntity<Map<String, Object>> executeTask(@PathVariable Long taskId) {
        vrpService.executeTask(taskId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("taskId", taskId);
        result.put("message", "任务执行完成");
        
        return ResponseEntity.ok(result);
    }

    /**
     * 查询任务结果
     * 
     * @param taskId 任务ID
     * @return 任务结果详情
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<TaskResultResponse> getTaskResult(@PathVariable Long taskId) {
        TaskResultResponse result = vrpService.getTaskResult(taskId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有任务列表
     * 
     * @return 任务列表
     */
    @GetMapping("/tasks")
    public ResponseEntity<List<TaskResultResponse>> getAllTasks() {
        List<TaskResultResponse> tasks = vrpService.getAllTasks();
        return ResponseEntity.ok(tasks);
    }

    // ==================== 数据管理接口 ====================

    /**
     * 生成测试数据
     * 
     * @param request 生成请求
     * @return 生成的节点数量
     */
    @PostMapping("/data/nodes/generate")
    public ResponseEntity<Map<String, Object>> generateTestData(
            @Valid @RequestBody NodeDataRequest request) {
        
        int count = vrpService.generateTestData(request);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("nodeCount", count);
        result.put("message", "成功生成 " + count + " 个配送点");
        
        return ResponseEntity.ok(result);
    }

    /**
     * 初始化车辆数据
     * 
     * @param count 车辆数量
     * @return 生成的车辆数量
     */
    @PostMapping("/data/vehicles/init")
    public ResponseEntity<Map<String, Object>> initVehicles(
            @RequestParam(defaultValue = "20") int count) {
        
        int vehicleCount = vrpService.initVehicles(count);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("vehicleCount", vehicleCount);
        result.put("message", "成功初始化 " + vehicleCount + " 辆车");
        
        return ResponseEntity.ok(result);
    }

    // ==================== 快速测试接口 ====================

    /**
     * 快速测试接口
     * 一键生成数据、初始化车辆并执行计算
     * 
     * @param nodeCount 配送点数量
     * @param vehicleCount 车辆数量
     * @return 计算结果
     */
    @PostMapping("/quick-test")
    public ResponseEntity<Map<String, Object>> quickTest(
            @RequestParam(defaultValue = "100") int nodeCount,
            @RequestParam(defaultValue = "10") int vehicleCount) {
        
        // 1. 生成测试数据
        NodeDataRequest nodeRequest = new NodeDataRequest();
        nodeRequest.setCount(nodeCount);
        nodeRequest.setRange(50.0);
        nodeRequest.setClearExisting(true);
        vrpService.generateTestData(nodeRequest);
        
        // 2. 初始化车辆
        vrpService.initVehicles(vehicleCount);
        
        // 3. 创建并执行任务
        TaskCreateRequest taskRequest = new TaskCreateRequest();
        taskRequest.setTaskName("快速测试任务");
        taskRequest.setVehicleCount(vehicleCount);
        Long taskId = vrpService.createAndExecuteTask(taskRequest);
        
        // 4. 获取结果
        TaskResultResponse result = vrpService.getTaskResult(taskId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("taskId", taskId);
        response.put("nodeCount", nodeCount);
        response.put("vehicleCount", vehicleCount);
        response.put("totalDistance", result.getTotalDistance());
        response.put("status", result.getStatusDesc());
        response.put("routes", result.getRoutes());
        
        return ResponseEntity.ok(response);
    }
}
