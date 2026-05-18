package com.example.vrpdemo.controller;

import com.example.vrpdemo.dto.*;
import com.example.vrpdemo.service.DistanceService;
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
    private final DistanceService distanceService;

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

    // ==================== 距离查询接口 ====================

    /**
     * 查询两地之间的最短道路距离
     * 通过Dijkstra算法计算，支持多跳路径（如 A->B->C->D）
     *
     * @param startNode 起点地名
     * @param endNode   终点地名
     * @return 最短道路距离（公里）及路径信息
     */
    @GetMapping("/distance")
    public ResponseEntity<Map<String, Object>> queryDistance(
            @RequestParam String startNode,
            @RequestParam String endNode) {

        Map<String, Object> result = new HashMap<>();

        if (!distanceService.nodeExists(startNode)) {
            result.put("success", false);
            result.put("message", "起点「" + startNode + "」不在道路网络中");
            return ResponseEntity.ok(result);
        }
        if (!distanceService.nodeExists(endNode)) {
            result.put("success", false);
            result.put("message", "终点「" + endNode + "」不在道路网络中");
            return ResponseEntity.ok(result);
        }

        if (!distanceService.isReachable(startNode, endNode)) {
            result.put("success", false);
            result.put("message", "从「" + startNode + "」到「" + endNode + "」不可达");
            return ResponseEntity.ok(result);
        }

        double distance = distanceService.getDistance(startNode, endNode);
        List<Map<String, Object>> path = distanceService.getShortestPathWithDistance(startNode, endNode);

        result.put("success", true);
        result.put("startNode", startNode);
        result.put("endNode", endNode);
        result.put("distanceKm", distance);
        result.put("path", path);
        return ResponseEntity.ok(result);
    }

    // ==================== 批量测试接口 ====================

    /**
     * 批量测试接口：多次执行同一任务，统计里程分布
     * 用于验证算法稳定性（多次运行看平均里程和波动）
     *
     * @param request     任务请求（taskName, vehicleCount）
     * @param repeatCount 重复执行次数
     * @return 统计结果（平均/最小/最大/每次详情）
     */
    @PostMapping("/batch-test")
    public ResponseEntity<Map<String, Object>> batchTest(
            @Valid @RequestBody TaskCreateRequest request,
            @RequestParam(defaultValue = "20") int repeatCount) {

        Map<String, Object> response = new HashMap<>();
        List<Double> distances = new java.util.ArrayList<>();
        List<Map<String, Object>> details = new java.util.ArrayList<>();

        long totalStart = System.currentTimeMillis();

        for (int i = 0; i < repeatCount; i++) {
            Long taskId = vrpService.createAndExecuteTask(request);
            TaskResultResponse result = vrpService.getTaskResult(taskId);
            if (result != null && result.getTotalDistance() != null) {
                distances.add(result.getTotalDistance());
                Map<String, Object> detail = new java.util.HashMap<>();
                detail.put("taskId", taskId);
                detail.put("distance", result.getTotalDistance());
                detail.put("gapRatio", result.getGapRatio());
                detail.put("nodeGapRatio", result.getNodeGapRatio());
                details.add(detail);
            }
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;

        // 统计
        double sum = 0, min = Double.MAX_VALUE, max = 0;
        int bestIdx = 0, worstIdx = 0;
        for (int i = 0; i < distances.size(); i++) {
            double d = distances.get(i);
            sum += d;
            if (d < min) { min = d; bestIdx = i; }
            if (d > max) { max = d; worstIdx = i; }
        }
        double avg = distances.isEmpty() ? 0 : sum / distances.size();

        // 极差（最大-最小）和标准差
        double variance = 0;
        for (double d : distances) {
            variance += (d - avg) * (d - avg);
        }
        double stdDev = distances.size() > 1 ? Math.sqrt(variance / (distances.size() - 1)) : 0;

        response.put("success", true);
        response.put("repeatCount", distances.size());
        response.put("avgDistance", Math.round(avg * 100.0) / 100.0);
        response.put("minDistance", Math.round(min * 100.0) / 100.0);
        response.put("maxDistance", Math.round(max * 100.0) / 100.0);
        response.put("range", Math.round((max - min) * 100.0) / 100.0);
        response.put("stdDev", Math.round(stdDev * 100.0) / 100.0);
        response.put("bestTaskId", details.isEmpty() ? null : ((Map<String, Object>) details.get(bestIdx)).get("taskId"));
        response.put("worstTaskId", details.isEmpty() ? null : ((Map<String, Object>) details.get(worstIdx)).get("taskId"));
        response.put("totalTimeSeconds", Math.round(totalElapsed / 1000.0 * 100.0) / 100.0);
        response.put("avgTimeSeconds", Math.round(totalElapsed / Math.max(distances.size(), 1) / 1000.0 * 100.0) / 100.0);
        response.put("details", details);

        return ResponseEntity.ok(response);
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
