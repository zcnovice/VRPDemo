package com.example.vrpdemo.service.impl;

import com.alibaba.fastjson2.JSON;
import com.example.vrpdemo.algorithm.SimulatedAnnealingAlgorithm;
import com.example.vrpdemo.dto.*;
import com.example.vrpdemo.entity.VrpNode;
import com.example.vrpdemo.entity.VrpRouteDetail;
import com.example.vrpdemo.entity.VrpTask;
import com.example.vrpdemo.entity.VrpVehicle;
import com.example.vrpdemo.mapper.VrpNodeMapper;
import com.example.vrpdemo.mapper.VrpRouteDetailMapper;
import com.example.vrpdemo.mapper.VrpTaskMapper;
import com.example.vrpdemo.mapper.VrpVehicleMapper;
import com.example.vrpdemo.service.DistanceService;
import com.example.vrpdemo.service.VrpService;
import com.example.vrpdemo.vo.NodeVO;
import com.example.vrpdemo.vo.SolutionVO;
import com.example.vrpdemo.vo.VehicleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * VRP业务服务实现类
 * 整合数据库操作和算法计算
 */
@Slf4j
@Service
/* 这个添加后相当于后面都添加了@Autowired注解（但是需要时带final关键字） */
@RequiredArgsConstructor
public class VrpServiceImpl implements VrpService {

    private final VrpNodeMapper nodeMapper;
    private final VrpVehicleMapper vehicleMapper;
    private final VrpTaskMapper taskMapper;
    private final VrpRouteDetailMapper routeDetailMapper;
    private final SimulatedAnnealingAlgorithm algorithm;
    private final DistanceService distanceService;

    // ==================== 任务管理 ====================

    /* 创建并执行VRP任务 */
    @Override
    @Transactional
    public Long createAndExecuteTask(TaskCreateRequest request) {
        // 创建任务
        Long taskId = createTask(request);
        // 执行任务
        executeTask(taskId);
        return taskId;
    }

    /* 创建VRP计算任务（不立即执行） */
    @Override
    @Transactional
    public Long createTask(TaskCreateRequest request) {
        VrpTask task = new VrpTask();
        task.setTaskName(request.getTaskName());
        task.setVehicleCount(request.getVehicleCount());
        task.setStatus(0); // 待计算
        task.setCreateTime(LocalDateTime.now());

        taskMapper.insert(task);
        log.info("创建VRP任务: taskId={}, taskName={}, vehicleCount={}",
                task.getId(), task.getTaskName(), task.getVehicleCount());

        return task.getId();
    }

    /* 执行VRP计算任务 */
    @Override
    @Transactional
    public void executeTask(Long taskId) {
        log.info("========== 开始执行任务: taskId={} ==========", taskId);
        
        VrpTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在: " + taskId);
        }

        try {
            // 更新状态为计算中
            task.setStatus(1);
            taskMapper.updateById(task);

            // 0. 刷新距离缓存(可选)
            //distanceService.loadDistanceCache();

            // 1. 从数据库加载数据
            /* 加载仓库节点 */
            NodeVO depot = loadDepot();
            /* 加载可到达的配送点节点 */
            List<NodeVO> deliveryNodes = loadDeliveryNodes(depot);
            /* 加载车辆数据 */
            List<VehicleVO> vehicles = loadVehicles(task.getVehicleCount());

            // 数据校验
            if (depot == null) {
                throw new RuntimeException("数据库中未配置仓库节点，请先添加仓库");
            }
            if (!distanceService.nodeExists(depot.getName())) {
                throw new RuntimeException("仓库节点[" + depot.getName() + "]在道路距离表中不存在，请检查数据");
            }
            if (deliveryNodes.isEmpty()) {
                throw new RuntimeException("没有可到达的配送点，请检查道路距离表数据");
            }
            if (vehicles.isEmpty()) {
                throw new RuntimeException("数据库中没有可用的车辆，请先初始化车辆数据");
            }

            log.info("开始计算: 仓库={}, 可达配送点数量={}, 车辆数量={}",
                    depot.getName(), deliveryNodes.size(), vehicles.size());

            // 打印各节点到仓库的最短距离
            distanceService.printDistancesToDepot(depot.getName());

            // 2. 执行算法求解
            SolutionVO solution = algorithm.solve(depot, deliveryNodes, vehicles);

            // 3. 保存结果
            saveTaskResult(task, solution);

            log.info("任务计算完成: taskId={}, totalDistance={}", taskId, solution.getTotalDistance());

        } catch (Exception e) {
            log.error("任务执行失败: taskId={}", taskId, e);
            task.setStatus(3); // 失败
            task.setResultJson("{\"error\":\"" + e.getMessage() + "\"}");
            taskMapper.updateById(task);
            throw new RuntimeException("任务执行失败: " + e.getMessage(), e);
        }
    }

    @Override
    public TaskResultResponse getTaskResult(Long taskId) {
        VrpTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }

        TaskResultResponse response = convertToResponse(task);

        // 如果任务完成，加载路线详情
        if (task.getStatus() == 2) {
            List<VrpRouteDetail> details = routeDetailMapper.selectByTaskId(taskId);
            response.setRoutes(buildRouteDetails(details));
        }

        return response;
    }

    @Override
    public List<TaskResultResponse> getAllTasks() {
        List<VrpTask> tasks = taskMapper.selectList(null);
        return tasks.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    // ==================== 数据加载 ====================

    /**
     * 加载仓库节点
     */
    private NodeVO loadDepot() {
        VrpNode depotEntity = nodeMapper.selectDepot();
        if (depotEntity == null) {
            return null;
        }
        return convertToNodeVO(depotEntity);
    }

    /**
     * 加载所有配送点（过滤不可达节点）
     * @param depot 仓库节点，用于过滤不可达的配送点
     */
    private List<NodeVO> loadDeliveryNodes(NodeVO depot) {
        List<VrpNode> entities = nodeMapper.selectActiveDeliveryPoints();
        
        List<NodeVO> reachableNodes = new ArrayList<>();
        int filteredCount = 0;
        
        for (VrpNode entity : entities) {
            NodeVO node = convertToNodeVO(entity);
            
            // 检查节点是否在距离表中存在
            if (!distanceService.nodeExists(node.getName())) {
                log.warn("配送点[{}]在道路距离表中不存在，已过滤", node.getName());
                filteredCount++;
                continue;
            }
            
            // 如果有仓库，检查是否可以从仓库到达
            if (depot != null && !distanceService.isReachable(depot.getName(), node.getName())) {
                log.warn("配送点[{}]无法从仓库[{}]到达，已过滤", node.getName(), depot.getName());
                filteredCount++;
                continue;
            }
            
            reachableNodes.add(node);
        }
        
        if (filteredCount > 0) {
            log.info("过滤不可达配送点: {} 个", filteredCount);
        }
        
        // 检查配送点之间的可达性
        if (depot != null && !reachableNodes.isEmpty()) {
            checkDeliveryNodesReachability(depot, reachableNodes);
        }
        
        return reachableNodes;
    }

    /**
     * 检查配送点之间的可达性
     * 如果存在不可达的情况，给出警告
     */
    private void checkDeliveryNodesReachability(NodeVO depot, List<NodeVO> nodes) {
        int unreachableCount = 0;
        List<String> unreachablePairs = new ArrayList<>();
        
        // 检查每两个节点之间是否可达（使用最短路径算法）
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                NodeVO n1 = nodes.get(i);
                NodeVO n2 = nodes.get(j);
                
                if (!distanceService.isReachable(n1.getName(), n2.getName())) {
                    unreachableCount++;
                    if (unreachablePairs.size() < 10) { // 只记录前10个
                        unreachablePairs.add(n1.getName() + " <-> " + n2.getName());
                    }
                }
            }
        }
        
        if (unreachableCount > 0) {
            log.warn("发现 {} 对配送点之间没有可达路径（可能由于道路网络不连通）", unreachableCount);
            for (String pair : unreachablePairs) {
                log.warn("  不可达: {}", pair);
            }
        } else {
            log.info("所有配送点之间均可到达");
        }
    }

    /**
     * 加载指定数量的车辆
     */
    private List<VehicleVO> loadVehicles(int count) {
        List<VrpVehicle> entities = vehicleMapper.selectAvailableVehicles(count);
        return entities.stream()
                .map(this::convertToVehicleVO)
                .collect(Collectors.toList());
    }

    // ==================== 结果保存 ====================

    /**
     * 保存任务结果
     */
    @Transactional
    private void saveTaskResult(VrpTask task, SolutionVO solution) {
        // 1. 更新任务信息
        task.setStatus(2); // 完成
        task.setTotalDistance(solution.getTotalDistance());
        task.setCompleteTime(LocalDateTime.now());
        task.setResultJson(JSON.toJSONString(solution));
        taskMapper.updateById(task);

        // 2. 删除旧的路线明细
        routeDetailMapper.deleteByTaskId(task.getId());

        // 3. 保存新的路线明细
        saveRouteDetails(task.getId(), solution);
    }

    /**
     * 保存路线明细
     */
    private void saveRouteDetails(Long taskId, SolutionVO solution) {
        List<VrpRouteDetail> details = new ArrayList<>();

        for (VehicleVO vehicle : solution.getVehicles()) {
            NodeVO prevNode = solution.getDepot();
            NodeVO lastNode = null;

            for (int i = 0; i < vehicle.getNodeCount(); i++) {
                NodeVO currentNode = vehicle.getRoute().get(i);

                VrpRouteDetail detail = new VrpRouteDetail();
                detail.setTaskId(taskId);
                detail.setVehicleId(vehicle.getId());
                detail.setSequence(i + 1);
                detail.setNodeId(currentNode.getId());
                detail.setDistanceFromPrev(prevNode.distanceTo(currentNode));
                detail.setCreateTime(LocalDateTime.now());

                details.add(detail);
                prevNode = currentNode;
                lastNode = currentNode;
            }

            // 补加返程段：最后一个节点 → 仓库
            if (lastNode != null) {
                VrpRouteDetail returnDetail = new VrpRouteDetail();
                returnDetail.setTaskId(taskId);
                returnDetail.setVehicleId(vehicle.getId());
                returnDetail.setSequence(vehicle.getNodeCount() + 1);
                returnDetail.setNodeId(solution.getDepot().getId());
                returnDetail.setDistanceFromPrev(lastNode.distanceTo(solution.getDepot()));
                returnDetail.setCreateTime(LocalDateTime.now());
                details.add(returnDetail);
            }
        }

        // 批量插入
        for (VrpRouteDetail detail : details) {
            routeDetailMapper.insert(detail);
        }

        log.info("保存路线明细: taskId={}, detailCount={}", taskId, details.size());
    }

    // ==================== 测试数据生成 ====================

    @Override
    @Transactional
    public int generateTestData(NodeDataRequest request) {
        // 清空现有配送点（保留仓库）
        if (request.getClearExisting()) {
            clearDeliveryPoints();
        }

        Random random = new Random();
        int count = 0;

        // 生成随机配送点
        for (int i = 1; i <= request.getCount(); i++) {
            VrpNode node = new VrpNode();
            node.setNodeCode("DP" + String.format("%04d", i));
            node.setName("配送点" + i);
            node.setXCoordinate((random.nextDouble() - 0.5) * 2 * request.getRange());
            node.setYCoordinate((random.nextDouble() - 0.5) * 2 * request.getRange());
            node.setNodeType(1); // 配送点
            node.setDemand(10.0 + random.nextDouble() * 20); // 随机需求量
            node.setStatus(1);
            node.setCreateTime(LocalDateTime.now());

            nodeMapper.insert(node);
            count++;
        }

        // 确保有仓库
        ensureDepotExists();

        log.info("生成测试数据完成: 配送点数量={}", count);
        return count;
    }

    @Override
    @Transactional
    public int initVehicles(int count) {
        // 清空现有车辆
        List<VrpVehicle> existing = vehicleMapper.selectList(null);
        for (VrpVehicle v : existing) {
            vehicleMapper.deleteById(v.getId());
        }

        // 生成新车辆
        for (int i = 1; i <= count; i++) {
            VrpVehicle vehicle = new VrpVehicle();
            vehicle.setVehicleCode("V" + String.format("%03d", i));
            vehicle.setCapacity(100.0); // 默认载重100
            vehicle.setStatus(1);
            vehicle.setCreateTime(LocalDateTime.now());

            vehicleMapper.insert(vehicle);
        }

        log.info("初始化车辆完成: 车辆数量={}", count);
        return count;
    }

    /**
     * 清空配送点数据
     */
    private void clearDeliveryPoints() {
        List<VrpNode> nodes = nodeMapper.selectList(null);
        for (VrpNode node : nodes) {
            if (node.getNodeType() == 1) { // 只删除配送点
                nodeMapper.deleteById(node.getId());
            }
        }
    }

    /**
     * 确保仓库存在
     */
    private void ensureDepotExists() {
        VrpNode depot = nodeMapper.selectDepot();
        if (depot == null) {
            depot = new VrpNode();
            depot.setNodeCode("DEPOT");
            depot.setName("配送中心");
            depot.setXCoordinate(0.0);
            depot.setYCoordinate(0.0);
            depot.setNodeType(2); // 仓库
            depot.setDemand(0.0);
            depot.setStatus(1);
            depot.setCreateTime(LocalDateTime.now());

            nodeMapper.insert(depot);
            log.info("创建默认仓库节点");
        }
    }

    // ==================== 数据转换 ====================

    /**
     * 实体转NodeVO
     */
    private NodeVO convertToNodeVO(VrpNode entity) {
        NodeVO vo = new NodeVO();
        vo.setId(entity.getId());
        vo.setNodeCode(entity.getNodeCode());
        vo.setName(entity.getName());
        vo.setX(entity.getXCoordinate());
        vo.setY(entity.getYCoordinate());
        vo.setDemand(entity.getDemand() != null ? entity.getDemand() : 0);
        return vo;
    }

    /**
     * 实体转VehicleVO
     */
    private VehicleVO convertToVehicleVO(VrpVehicle entity) {
        return new VehicleVO(entity.getId(), entity.getVehicleCode(), 
                entity.getCapacity() != null ? entity.getCapacity() : 100);
    }

    /**
     * 任务实体转响应DTO
     */
    private TaskResultResponse convertToResponse(VrpTask task) {
        TaskResultResponse response = new TaskResultResponse();
        response.setTaskId(task.getId());
        response.setTaskName(task.getTaskName());
        response.setVehicleCount(task.getVehicleCount());
        response.setStatus(task.getStatus());
        response.setTotalDistance(task.getTotalDistance());
        response.setCreateTime(task.getCreateTime());
        response.setCompleteTime(task.getCompleteTime());
        return response;
    }

    /**
     * 构建路线详情列表
     */
    private List<VehicleRouteDTO> buildRouteDetails(List<VrpRouteDetail> details) {
        // 按车辆分组
        Map<Long, List<VrpRouteDetail>> groupedByVehicle = details.stream()
                .collect(Collectors.groupingBy(VrpRouteDetail::getVehicleId));

        List<VehicleRouteDTO> routes = new ArrayList<>();

        for (Map.Entry<Long, List<VrpRouteDetail>> entry : groupedByVehicle.entrySet()) {
            VehicleRouteDTO route = new VehicleRouteDTO();
            route.setVehicleId(entry.getKey());

            // 获取车辆信息
            VrpVehicle vehicle = vehicleMapper.selectById(entry.getKey());
            if (vehicle != null) {
                route.setVehicleCode(vehicle.getVehicleCode());
            }

            // 构建节点列表
            List<RouteNodeDTO> nodes = new ArrayList<>();
            for (VrpRouteDetail detail : entry.getValue()) {
                VrpNode nodeEntity = nodeMapper.selectById(detail.getNodeId());
                if (nodeEntity != null) {
                    RouteNodeDTO node = new RouteNodeDTO();
                    node.setNodeId(nodeEntity.getId());
                    node.setNodeCode(nodeEntity.getNodeCode());
                    node.setNodeName(nodeEntity.getName());
                    node.setX(nodeEntity.getXCoordinate());
                    node.setY(nodeEntity.getYCoordinate());
                    node.setSequence(detail.getSequence());
                    node.setDistanceFromPrev(detail.getDistanceFromPrev());
                    node.setDemand(nodeEntity.getDemand());
                    nodes.add(node);
                }
            }

            // 按顺序排序
            nodes.sort(Comparator.comparingInt(RouteNodeDTO::getSequence));
            route.setNodes(nodes);
            route.setNodeCount(nodes.size());

            // 计算总距离
            double totalDist = nodes.stream()
                    .mapToDouble(n -> n.getDistanceFromPrev() != null ? n.getDistanceFromPrev() : 0)
                    .sum();
            route.setTotalDistance(totalDist);

            routes.add(route);
        }

        return routes;
    }
}
