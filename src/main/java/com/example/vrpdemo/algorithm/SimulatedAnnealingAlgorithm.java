package com.example.vrpdemo.algorithm;

import com.example.vrpdemo.config.VrpAlgorithmConfig;
import com.example.vrpdemo.vo.NodeVO;
import com.example.vrpdemo.vo.SolutionVO;
import com.example.vrpdemo.vo.VehicleVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 模拟退火算法求解VRP问题
 * 
 * 算法核心思想：
 * 1. 通过扇形区域分配初始化解，将节点按角度分配给不同车辆
 * 2. 通过邻域操作（交换、移动节点）生成新解
 * 3. 以一定概率接受较差解，避免陷入局部最优
 * 4. 随着温度降低，逐渐收敛到较优解
 * 
 * 目标函数：
 * 综合评分 = 权重1*总里程 + 权重2*聚类评分 + 权重3*均衡评分
 */
@Slf4j
@Component
public class SimulatedAnnealingAlgorithm {

    private final VrpAlgorithmConfig config;

    /* 仓库节点 */
    private NodeVO depot;

    /* 所有配送点 */
    private List<NodeVO> deliveryNodes;

    /* 扇形区域信息：车辆ID -> [主区域起始, 主区域结束, 扩展区起始, 扩展区结束] */
    private Map<Long, double[]> vehicleSectors;

    /* 基础角度（每辆车的主责任区角度） */
    private double baseAngle;

    /* 算法开始时间 */
    private long startTime;

    /* 自适应参考值（从初始解计算，用于评分归一化） */
    private double refTotalDistance;
    private double refCluster;
    private double refBalance;
    private double refGapRatio;

    public SimulatedAnnealingAlgorithm(VrpAlgorithmConfig config) {
        this.config = config;
    }

    /**
     * 执行VRP求解
     * 
     * @param depot 仓库节点（配送中心）
     * @param nodes 所有配送点列表
     * @param vehicles 可用车辆列表
     * @return 最优解
     */
    public SolutionVO solve(NodeVO depot, List<NodeVO> nodes, List<VehicleVO> vehicles) {
        // 初始化
        this.depot = depot;
        this.deliveryNodes = nodes;
        this.vehicleSectors = new HashMap<>();
        this.startTime = System.currentTimeMillis();

        log.info("========== VRP求解开始 ==========");
        log.info("配送点数量: {}, 车辆数量: {}", nodes.size(), vehicles.size());

        // 提取车辆ID列表
        List<Long> vehicleIds = vehicles.stream().map(VehicleVO::getId).toList();

        // 1. 创建初始解
        SolutionVO currentSolution = createInitialSolution(vehicles, nodes);

        // 2. 先计算初始解的各项指标（总里程、聚类、均衡、极差/平均），用于设置参考值
        currentSolution.calculateMetrics();

        // 使用配置中的固定参考值（用于评分归一化）
        this.refTotalDistance = config.getRefTotalDistance();
        this.refCluster = config.getRefCluster();
        // balanceScore = (cv + penalty) * avg，范围太广，用avg作为基准
        double avgDistance = currentSolution.getTotalDistance() / vehicles.size();
        this.refBalance = avgDistance;  // 归一化后cv+penalty的量级就是得分
        // gapRatioScore = (max-min)/avg，本身已经是归一化指标，参考值设为1.0
        this.refGapRatio = 0.2;
        log.info("参考值: 总里程={}, 聚类={}, 均衡(avg)={}, 极差/平均={}",
                String.format("%.0f", refTotalDistance),
                String.format("%.1f", refCluster),
                String.format("%.0f", refBalance),
                String.format("%.2f", refGapRatio));

        // 用正确的 ref 重算初始解得分（createInitialSolution 内 ref=0，导致得分未归一化）
        currentSolution.calculateScore(
                config.getWeightDistance(),
                config.getWeightCluster(),
                config.getWeightBalance(),
                config.getWeightGapRatio(),
                refTotalDistance,
                refCluster,
                refBalance,
                refGapRatio
        );

        /* 初始解深拷贝给最优解，防止后续迭代引用同一对象 */
        SolutionVO bestSolution = currentSolution.deepCopy();
        log.info("初始解得分: {}", String.format("%.4f", bestSolution.getScore()));

        // 2. 模拟退火主循环
        double temperature = config.getInitialTemperature();
        /* 迭代次数计数器 */
        int iteration = 0;
        /* 停滞计数器：连续未改善最优解的迭代次数 */
        int stagnationCount = 0;
        final int STAGNATION_LIMIT = 50;  // 连续50次未改善则回退到最优解

        while (temperature > config.getFinalTemperature()) {
            iteration++;

            // 生成邻域解
            SolutionVO newSolution = generateNeighbor(currentSolution, vehicleIds);

            // 计算得分差
            double delta = newSolution.getScore() - currentSolution.getScore();

            // Metropolis接受准则
            if (delta < 0) {
                // 新解更好，无条件接受
                currentSolution = newSolution;
                if (currentSolution.getScore() < bestSolution.getScore()) {
                    bestSolution = currentSolution;
                    stagnationCount = 0;  // 改善了最优解，重置停滞计数
                }
            } else {
                // 新解更差，以概率接受
                double acceptProbability = Math.exp(-delta / temperature);
                if (acceptProbability > Math.random()) {
                    currentSolution = newSolution;
                }
                stagnationCount++;
            }

            // 停滞回退：连续未改善最优解时，回退到最优解并小幅升温
            if (stagnationCount >= STAGNATION_LIMIT) {
                currentSolution = bestSolution.deepCopy();
                temperature = Math.max(temperature * 1.5, 0.01);  // 小幅升温，给算法重新探索的活力
                stagnationCount = 0;
                log.info("停滞{}次，回退到最优解并升温至 {}", STAGNATION_LIMIT,
                        String.format("%.4f", temperature));
            }

            // 降温
            temperature *= config.getCoolingRate();

            // 日志输出
            if (iteration % config.getLogInterval() == 0) {
                log.info("迭代: {}, 温度: {}, 当前得分: {}, 最优得分: {}",
                        iteration,
                        String.format("%.4f", temperature),
                        String.format("%.4f", currentSolution.getScore()),
                        String.format("%.4f", bestSolution.getScore()));
            }
        }

        // 3. 输出最终结果
        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("========== VRP求解完成 ==========");
        log.info("总迭代次数: {}", iteration);
        log.info("最优得分: {}", String.format("%.4f", bestSolution.getScore()));
        log.info("总里程: {}", String.format("%.2f", bestSolution.getTotalDistance()));
        log.info("耗时: {}秒", String.format("%.2f", elapsedTime / 1000.0));

        // 4. 顺路捎带优化：让车辆带上途经的节点（先执行，不破坏后续均衡）
        performDetourPickup(bestSolution, vehicleIds);
        log.info("顺路捎带后总里程: {}", String.format("%.2f", bestSolution.getTotalDistance()));

        // 5. 专项均衡优化：缩小最大最小里程差距（最后执行，保证成果不被覆盖）    缩小里程差距，但是会增加总里程，还需要优化
        performBalanceRefinement(bestSolution, vehicleIds);

        // 6. 兜底微调：如果最低里程车仍偏低，从最高车移节点给它
        //performGapClosing(bestSolution, vehicleIds);

        return bestSolution;
    }

    // ==================== 初始解生成 ====================

    /**
     * 创建初始解
     * 使用扇形区域分配策略，将节点按角度分配给不同车辆
     *
     * @param vehicles 可用车辆列表
     * @param nodes 所有配送点列表
     * @return 初始解
     */
    private SolutionVO createInitialSolution(List<VehicleVO> vehicles, List<NodeVO> nodes) {
        SolutionVO solution = new SolutionVO(depot);
        int numVehicles = vehicles.size();

        // 1. 计算基础角度和扇形区域
        baseAngle = 360.0 / numVehicles;
        double overlapAngle = baseAngle * config.getOverlapRatio();

        // 2. 为每辆车分配扇形区域
        for (int i = 0; i < numVehicles; i++) {
            VehicleVO vehicle = vehicles.get(i);

            // 主责任区角度范围
            double primaryStart = (i * baseAngle) % 360;
            double primaryEnd = ((i + 1) * baseAngle) % 360;

            // 扩展区（向两边扩展overlapAngle/2）
            double extendedStart = (primaryStart - overlapAngle / 2 + 360) % 360;
            double extendedEnd = (primaryEnd + overlapAngle / 2) % 360;

            // 存储扇形信息
            double[] sector = {primaryStart, primaryEnd, extendedStart, extendedEnd};
            vehicle.setSector(sector);
            vehicleSectors.put(vehicle.getId(), sector);

            solution.addVehicle(vehicle);
        }

        // 3. 计算所有节点相对于仓库的角度
        for (NodeVO node : nodes) {
            node.calculateAngle(depot.getX(), depot.getY());
        }

        // 4. 将节点分配到对应扇形区域
        for (NodeVO node : nodes) {
            double nodeAngle = node.getAngle();

            // 找到该角度对应的主责任区车辆
            int primaryIndex = (int) (nodeAngle / baseAngle);
            primaryIndex = Math.min(primaryIndex, numVehicles - 1);

            // 分配给对应车辆
            VehicleVO vehicle = solution.getVehicle(vehicles.get(primaryIndex).getId());
            if (vehicle != null) {
                vehicle.addNode(node);
            }
        }

        // 5. 负载均衡优化：过载车辆向相邻车辆溢出节点
        balanceLoad(solution, vehicles);

        // 评分计算移到solve()中，在设置参考值之后执行

        return solution;
    }

    /**
     * 负载均衡优化（按里程均衡，而非节点数）
     * 从高里程车辆移动节点到低里程车辆，直到差距收敛
     * 节点数不重要，重要的是里程要平均
     */
    private void balanceLoad(SolutionVO solution, List<VehicleVO> vehicles) {
        int numVehicles = vehicles.size();
        int minNodesPerVehicle = 3; // 每车最少保留节点数
        List<Long> vehicleIds = vehicles.stream().map(VehicleVO::getId).toList();

        log.info("负载均衡开始（按里程均衡）");

        // 多轮迭代，从高里程车移节点到低里程车
        for (int round = 0; round < 20; round++) {
            // 计算所有车辆里程，找最大最小
            double maxDist = 0, minDist = Double.MAX_VALUE;
            Long maxVId = null, minVId = null;
            double sumDist = 0;

            for (Long vid : vehicleIds) {
                double dist = solution.getVehicle(vid).calculateDistance(depot);
                sumDist += dist;
                if (dist > maxDist) { maxDist = dist; maxVId = vid; }
                if (dist < minDist) { minDist = dist; minVId = vid; }
            }

            double avgDist = sumDist / numVehicles;
            double gapRatio = avgDist > 0 ? (maxDist - minDist) / avgDist : 0;

            // 差距在40%以内或无法继续时停止（留给SA主循环优化）
            if (gapRatio <= 0.40) {
                log.info("负载均衡完成: 差距比={}% (目标40%)", String.format("%.1f", gapRatio * 100));
                break;
            }

            VehicleVO maxVehicle = solution.getVehicle(maxVId);
            VehicleVO minVehicle = solution.getVehicle(minVId);

            // 高里程车节点数不足时跳过
            if (maxVehicle.getNodeCount() <= minNodesPerVehicle) {
                // 尝试找次高里程车
                boolean found = false;
                for (Long vid : vehicleIds) {
                    if (vid.equals(maxVId)) continue;
                    VehicleVO v = solution.getVehicle(vid);
                    if (v.calculateDistance(depot) > avgDist && v.getNodeCount() > minNodesPerVehicle) {
                        maxVehicle = v;
                        maxVId = vid;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    log.info("负载均衡: 高里程车均不足{}个节点，停止", minNodesPerVehicle);
                    break;
                }
            }

            // 从高里程车移动一个节点到低里程车
            boolean moved = moveNodeWithDistanceCheck(maxVehicle, minVehicle, true);
            if (!moved) {
                log.info("负载均衡: 第{}轮无法移动节点，停止。差距比={}",
                        round, String.format("%.1f", gapRatio * 100));
                break;
            }
        }

        // 打印均衡后的结果
        StringBuilder sb = new StringBuilder("负载均衡后各车辆里程: ");
        for (Long vId : vehicleIds) {
            sb.append(String.format("%.0f", solution.getVehicle(vId).calculateDistance(depot))).append("km, ");
        }
        log.info(sb.substring(0, sb.length() - 2));

        StringBuilder sbNodes = new StringBuilder("负载均衡后各车辆节点数: ");
        for (Long vId : vehicleIds) {
            sbNodes.append(solution.getVehicle(vId).getNodeCount()).append(", ");
        }
        log.info(sbNodes.substring(0, sbNodes.length() - 2));
    }

    /**
     * 将节点从一辆车移动到另一辆车，考虑距离因素
     * @param fromVehicle 源车辆
     * @param toVehicle 目标车辆
     * @param relaxSector 是否放宽扇形限制
     * @return 是否成功移动
     */
    private boolean moveNodeWithDistanceCheck(VehicleVO fromVehicle, VehicleVO toVehicle, boolean relaxSector) {
        if (fromVehicle.getNodeCount() == 0) {
            return false;
        }

        // 找到距离目标车辆路线最近的节点
        int bestIdx = -1;
        double minExtraDistance = Double.MAX_VALUE;
        
        for (int i = 0; i < fromVehicle.getNodeCount(); i++) {
            NodeVO node = fromVehicle.getRoute().get(i);
            
            // 检查扇形区域（可放宽）
            /* 检查：源车辆的节点是否在目标车辆的扩展区域内 */
            /* relaxSector = false   and  并且源车辆节点不在目标车辆的区域内   就会跳过*/
            if (!relaxSector && !isNodeInSector(node, toVehicle.getId())) {
                continue; /* 不在目标车辆扩展区域内，跳过  但是这里放宽了限制（relaxSector = true）不在也可以 */
            }
            
            // 计算插入到目标车辆的最佳位置
            double extraDist = calculateBestInsertionCost(toVehicle, node);
            if (extraDist < minExtraDistance) {
                minExtraDistance = extraDist;
                bestIdx = i;
            }
        }

        if (bestIdx >= 0) {
            NodeVO node = fromVehicle.removeNode(bestIdx);
            insertAtBestPosition(toVehicle, node);
            return true;
        }

        return false;
    }

    /**
     * 计算将节点插入到车辆的最佳位置的额外距离
     */
    private double calculateBestInsertionCost(VehicleVO vehicle, NodeVO node) {
        if (vehicle.getNodeCount() == 0) {
            return depot.distanceTo(node) * 2;
        }

        double minCost = Double.MAX_VALUE;
        for (int pos = 0; pos <= vehicle.getNodeCount(); pos++) {
            double cost = calculateInsertionCost(vehicle, node, pos);
            if (cost < minCost) {
                minCost = cost;
            }
        }
        return minCost;
    }

    /**
     * 将节点插入到车辆的最佳位置
     */
    private void insertAtBestPosition(VehicleVO vehicle, NodeVO node) {
        if (vehicle.getNodeCount() == 0) {
            vehicle.addNode(node);
            return;
        }

        int bestPos = 0;
        double minCost = calculateInsertionCost(vehicle, node, 0);
        
        for (int pos = 1; pos <= vehicle.getNodeCount(); pos++) {
            double cost = calculateInsertionCost(vehicle, node, pos);
            if (cost < minCost) {
                minCost = cost;
                bestPos = pos;
            }
        }
        
        vehicle.insertNode(bestPos, node);
    }

    // ==================== 邻域操作 ====================

    /**
     * 生成邻域解
     * 通过交换或移动节点产生新解
     * @param current 当前解
     * @param vehicleIds 可用车辆id列表
     * @return 邻域解
     */
    private SolutionVO generateNeighbor(SolutionVO current, List<Long> vehicleIds) {
        // 深拷贝当前解
        SolutionVO newSolution = current.deepCopy();

        Random rand = new Random();

        /* 随机选择两辆车 */
        int idx1 = rand.nextInt(vehicleIds.size());
        int idx2 = rand.nextInt(vehicleIds.size());
        while (idx1 == idx2) {
            idx2 = rand.nextInt(vehicleIds.size());
        }

        VehicleVO v1 = newSolution.getVehicle(vehicleIds.get(idx1));
        VehicleVO v2 = newSolution.getVehicle(vehicleIds.get(idx2));

        /* 计算负载情况（节点数量与距离的负载是否均衡） */

        /* 平均每辆车的配送点数量 */
        double avgNodes = deliveryNodes.size() / (double) vehicleIds.size();
        /* 计算两辆车到仓库的距离（总距离） */
        double dist1 = v1.calculateDistance(depot);
        double dist2 = v2.calculateDistance(depot);

        /* 节点数量不均衡（超过30%），或者距离不均衡（超过1.3倍） */
        boolean severeImbalance = (Math.abs(v1.getNodeCount() - v2.getNodeCount()) > avgNodes * 0.3)
                || (Math.max(dist1, dist2) > Math.min(dist1, dist2) * 1.3);

        // 选择操作类型
        int moveType;
        if (severeImbalance) {
            moveType = rand.nextInt(3); // 0:交换, 1:移动, 2:均衡移动
        } else {
            // 正常情况也有50%概率触发均衡移动
            if (rand.nextDouble() < 0.5) {
                moveType = 2; // 均衡移动
            } else {
                moveType = rand.nextInt(2); // 0:交换, 1:移动
            }
        }

        // 执行操作
        try {
            switch (moveType) {
                case 0 -> performSwap(v1, v2, severeImbalance);
                case 1 -> performMove(v1, v2);
                case 2 -> performBalanceMove(v1, v2, dist1, dist2);
            }
        } catch (Exception e) {
            // 忽略异常操作
        }

        // 计算新解评分
        newSolution.calculateScore(
                config.getWeightDistance(),
                config.getWeightCluster(),
                config.getWeightBalance(),
                config.getWeightGapRatio(),
                refTotalDistance,
                refCluster,
                refBalance,
                refGapRatio
        );

        return newSolution;
    }

    /**
     * 执行交换操作
     * 两辆车互换一个节点
     */
    private void performSwap(VehicleVO v1, VehicleVO v2, boolean severeImbalance) {
        if (v1.getNodeCount() == 0 || v2.getNodeCount() == 0) {
            return;
        }

        Random rand = new Random();
        /* 有效交换列表 */
        List<int[]> validPairs = new ArrayList<>();

        // 找到有效的交换对
        for (int i = 0; i < v1.getNodeCount(); i++) {
            for (int j = 0; j < v2.getNodeCount(); j++) {
                NodeVO n1 = v1.getRoute().get(i);
                NodeVO n2 = v2.getRoute().get(j);

                /* 节点是否在对应车辆的扇形区域内 n2 节点是否在v1车辆的扇形区域内 */
                boolean n2InV1 = isNodeInSector(n2, v1.getId());
                boolean n1InV2 = isNodeInSector(n1, v2.getId());


                /* 决定什么节点可以加入有效交换列表 */
                // 统一要求：两个节点必须在对方的扩展扇形区域内（防止地理漂移）
                if (n2InV1 && n1InV2) {
                    // 严重不均衡时：扇形内即可交换（放宽距离约束）
                    // 正常情况：扇形内且交换后距离差缩小
                    if (severeImbalance || canImproveBalance(v1, v2, i, j)) {
                        validPairs.add(new int[]{i, j});
                    }
                }
            }
        }

        // 随机选择一对进行交换
        if (!validPairs.isEmpty()) {
            /* 随机获取到 validPairs 中的一个节点交换对 */
            int[] pair = validPairs.get(rand.nextInt(validPairs.size()));
            /* pair中第一个是pair[0]  n1这个节点在v1车辆的路径中索引 ，pair[1]  n2这个节点在v2车辆的路径中索引 */
            NodeVO n1 = v1.getRoute().get(pair[0]);
            NodeVO n2 = v2.getRoute().get(pair[1]);

            /* 进行交换 */
            v1.getRoute().set(pair[0], n2);
            v2.getRoute().set(pair[1], n1);
        }
    }

    /**
     * 执行移动操作
     * 将v1的一个节点移动到v2
     */
    private void performMove(VehicleVO v1, VehicleVO v2) {
        if (v1.getNodeCount() == 0) {
            return;
        }

        Random rand = new Random();
        List<Integer> validIndices = new ArrayList<>();

        // 找到在v2扇形区域内的节点
        for (int i = 0; i < v1.getNodeCount(); i++) {
            NodeVO node = v1.getRoute().get(i);
            /* 判断v1里面的这个节点是否在v2的扇形区域内 */
            if (isNodeInSector(node, v2.getId())) {
                validIndices.add(i);
            }
        }

        // 随机移动一个节点
        if (!validIndices.isEmpty()) {
            /* 随机获取一个节点来移动 */
            int idx = validIndices.get(rand.nextInt(validIndices.size()));
            NodeVO node = v1.removeNode(idx);
            v2.addNode(node);
        }
    }

    /**
     * 执行负载均衡移动
     * 从高负载车辆向低负载车辆移动节点
     * @param v1 车辆1
     * @param v2 车辆2
     * @param dist1 车辆1到仓库的距离
     * @param dist2 车辆2到仓库的距离
     */
    private void performBalanceMove(VehicleVO v1, VehicleVO v2, double dist1, double dist2) {
        // 确定哪辆车负载更高
        VehicleVO fromVehicle, toVehicle;
        Long toId;

        /* 节点多或者距离比较远的车辆就是  高负载车辆 */
        if (v1.getNodeCount() > v2.getNodeCount() || dist1 > dist2) {
            /* 从v1车辆中选择一个节点移动到v2车辆中 */
            fromVehicle = v1;
            toVehicle = v2;
            toId = v2.getId();
        } else {
            /* 反之 */
            fromVehicle = v2;
            toVehicle = v1;
            toId = v1.getId();
        }

        if (fromVehicle.getNodeCount() == 0) {
            return;
        }

        Random rand = new Random();

        // 使用扇形扩展区域检查（与普通移动一致，防止地理漂移）
        List<Integer> candidates = new ArrayList<>();

        for (int i = 0; i < fromVehicle.getNodeCount(); i++) {
            NodeVO node = fromVehicle.getRoute().get(i);
            if (isNodeInSector(node, toId)) {
                candidates.add(i);
            }
        }

        if (!candidates.isEmpty()) {
            int idx = candidates.get(rand.nextInt(candidates.size()));
            NodeVO node = fromVehicle.removeNode(idx);
            toVehicle.addNode(node);
        }
    }

    /**
     * 判断节点是否在指定车辆的扇形区域内(包含扩展区域)
     * 如果交换后的距离差异小于当前距离差异，则交换
     */
    private boolean isNodeInSector(NodeVO node, Long vehicleId) {
        double[] sector = vehicleSectors.get(vehicleId);
        if (sector == null) {
            return true;
        }

        double nodeAngle = node.getAngle();
        double extendedMin = sector[2];
        double extendedMax = sector[3];

        if (extendedMin > extendedMax) {
            return nodeAngle >= extendedMin || nodeAngle <= extendedMax;
        } else {
            return nodeAngle >= extendedMin && nodeAngle <= extendedMax;
        }
    }

    /**
     * 判断交换是否能改善负载均衡（带地理约束）
     * 必须同时满足：1) 两个节点在对方的扩展扇形区域内 2) 交换后距离差缩小
     * @param v1 车辆1
     * @param v2 车辆2
     * @param idx1 车辆1的节点索引
     * @param idx2 车辆2的节点索引
     */
    private boolean canImproveBalance(VehicleVO v1, VehicleVO v2, int idx1, int idx2) {
        NodeVO n1 = v1.getRoute().get(idx1);
        NodeVO n2 = v2.getRoute().get(idx2);

        // 地理约束：两个节点必须在对方的扩展扇形区域内，防止跨扇形漂移
        if (!isNodeInSector(n1, v2.getId()) || !isNodeInSector(n2, v1.getId())) {
            return false;
        }

        /* 计算当前两辆车到仓库的距离差异 */
        double currentDiff = Math.abs(v1.calculateDistance(depot) - v2.calculateDistance(depot));

        // 使用移除/插入成本估算交换后的差异（基于道路距离）
        double removeSavingV1 = calculateRemovalSaving(v1, idx1);
        double insertCostV1 = calculateBestInsertionCost(v1, n2);
        double v1New = v1.calculateDistance(depot) - removeSavingV1 + insertCostV1;

        double removeSavingV2 = calculateRemovalSaving(v2, idx2);
        double insertCostV2 = calculateBestInsertionCost(v2, n1);
        double v2New = v2.calculateDistance(depot) - removeSavingV2 + insertCostV2;

        double newDiff = Math.abs(v1New - v2New);
        return newDiff < currentDiff;
    }

    // ==================== 专项均衡优化 ====================

    /**
     * 专项均衡优化
     * 迭代地从里程最高的车辆向里程最低的车辆移动节点，缩小最大最小差距
     * 目标：最大里程/最小里程差距在10%以内
     */
    private void performBalanceRefinement(SolutionVO solution, List<Long> vehicleIds) {
        log.info("========== 执行专项均衡优化 ==========");

        int maxIterations = 500;
        double targetGapRatio = 0.10;
        double maxDistIncreaseRatio = 0.10; // 总里程最多增加10%
        int moveCount = 0;

        // 记录初始总里程作为约束基准
        double baselineTotalDist = 0;
        for (Long vid : vehicleIds) {
            baselineTotalDist += solution.getVehicle(vid).calculateDistance(depot);
        }
        double maxAllowedDist = baselineTotalDist * (1 + maxDistIncreaseRatio);

        for (int iter = 0; iter < maxIterations; iter++) {
            // 计算所有车辆里程，找出最大最小
            double maxDist = 0, minDist = Double.MAX_VALUE;
            Long maxVId = null, minVId = null;
            double sumDist = 0;

            for (Long vid : vehicleIds) {
                double dist = solution.getVehicle(vid).calculateDistance(depot);
                sumDist += dist;
                if (dist > maxDist) {
                    maxDist = dist;
                    maxVId = vid;
                }
                if (dist < minDist) {
                    minDist = dist;
                    minVId = vid;
                }
            }

            double avgDist = sumDist / vehicleIds.size();
            double gapRatio = avgDist > 0 ? (maxDist - minDist) / avgDist : 0;

            if (gapRatio <= targetGapRatio) {
                log.info("专项均衡优化: 差距比={}% 已达标(目标10%)，共移动{}个节点",
                        String.format("%.1f", gapRatio * 100), moveCount);
                break;
            }

            VehicleVO maxVehicle = solution.getVehicle(maxVId);
            VehicleVO minVehicle = solution.getVehicle(minVId);

            // 高里程车只剩1个节点时无法继续移动
            if (maxVehicle.getNodeCount() <= 1) {
                log.info("专项均衡优化: 高里程车V{}仅剩{}个节点，停止", maxVId, maxVehicle.getNodeCount());
                break;
            }

            // 从高里程车的每个节点中，找到移动到任一车辆后全局差距最小的方案
            // 约束1：总里程增加不超过5%
            // 约束2：节点必须在目标车辆的扇形扩展区域内（地理聚类约束）
            int bestNodeIdx = -1;
            Long bestTargetVId = null;
            double bestGlobalGap = maxDist - minDist;
            double bestNewTotalDist = sumDist;

            for (int i = 0; i < maxVehicle.getNodeCount(); i++) {
                NodeVO node = maxVehicle.getRoute().get(i);
                double removalSaving = calculateRemovalSaving(maxVehicle, i);
                double hNew = maxDist - removalSaving;

                // 尝试插入到每个其他车辆，找全局最优（无扇形约束，允许跨区域均衡）
                for (Long targetVId : vehicleIds) {
                    if (targetVId.equals(maxVId)) continue;

                    VehicleVO targetVehicle = solution.getVehicle(targetVId);
                    double targetDist = targetVehicle.calculateDistance(depot);
                    double insertionCost = calculateBestInsertionCost(targetVehicle, node);
                    double tNew = targetDist + insertionCost;

                    // 计算新的总里程
                    double newTotalDist = sumDist - removalSaving + insertionCost;
                    // 总里程约束
                    if (newTotalDist > maxAllowedDist) continue;

                    // 计算移动后的全局最大最小差距
                    double newMax = hNew, newMin = tNew;
                    if (hNew < tNew) { newMax = tNew; newMin = hNew; }
                    for (Long vid : vehicleIds) {
                        if (vid.equals(maxVId) || vid.equals(targetVId)) continue;
                        double d = solution.getVehicle(vid).calculateDistance(depot);
                        if (d > newMax) newMax = d;
                        if (d < newMin) newMin = d;
                    }

                    double newGap = newMax - newMin;
                    // 优先选差距最小的，差距相同时选总里程增加最少的
                    if (newGap < bestGlobalGap || (newGap == bestGlobalGap && newTotalDist < bestNewTotalDist)) {
                        bestGlobalGap = newGap;
                        bestNodeIdx = i;
                        bestTargetVId = targetVId;
                        bestNewTotalDist = newTotalDist;
                    }
                }
            }

            if (bestNodeIdx >= 0 && bestTargetVId != null) {
                // 执行最佳移动
                NodeVO movedNode = maxVehicle.removeNode(bestNodeIdx);
                VehicleVO targetVehicle = solution.getVehicle(bestTargetVId);
                insertAtBestPosition(targetVehicle, movedNode);
                moveCount++;

                if (iter % 20 == 0) {
                    log.info("专项均衡优化第{}轮: 移动节点[{}] V{}->V{}, 全局差距={}km, 总里程={}km",
                            iter, movedNode.getName(), maxVId, bestTargetVId,
                            String.format("%.0f", bestGlobalGap),
                            String.format("%.0f", bestNewTotalDist));
                }
            } else {
                // 移动无法改善（可能受总里程约束），尝试交换
                boolean swapped = tryBalanceSwap(solution, maxVehicle, minVehicle, maxDist, minDist);
                if (!swapped) {
                    log.info("专项均衡优化: 第{}轮无法改善，停止。差距比={}, 总里程={}km",
                            iter, String.format("%.1f", gapRatio * 100),
                            String.format("%.0f", sumDist));
                    break;
                }
                moveCount++;
            }
        }

        // 重新计算评分
        solution.calculateScore(
                config.getWeightDistance(),
                config.getWeightCluster(),
                config.getWeightBalance(),
                config.getWeightGapRatio(),
                refTotalDistance,
                refCluster,
                refBalance,
                refGapRatio
        );

        // 打印最终各车里程
        StringBuilder sb = new StringBuilder("专项均衡优化后各车辆里程: ");
        for (Long vid : vehicleIds) {
            sb.append(String.format("%.0f", solution.getVehicle(vid).calculateDistance(depot)));
            sb.append("km, ");
        }
        log.info(sb.substring(0, sb.length() - 2));
    }

    /**
     * 计算从车辆路线中移除指定位置节点后的距离节省
     * @param vehicle 车辆
     * @param pos 节点在路线中的位置
     * @return 移除后节省的距离（正值）
     */
    private double calculateRemovalSaving(VehicleVO vehicle, int pos) {
        List<NodeVO> route = vehicle.getRoute();
        NodeVO node = route.get(pos);

        if (route.size() == 1) {
            // 只有一个节点，移除后节省往返距离
            return depot.distanceTo(node) * 2;
        }

        if (pos == 0) {
            // 首节点：depot -> node -> next 变为 depot -> next
            NodeVO next = route.get(1);
            return depot.distanceTo(node) + node.distanceTo(next) - depot.distanceTo(next);
        }

        if (pos == route.size() - 1) {
            // 尾节点：prev -> node -> depot 变为 prev -> depot
            NodeVO prev = route.get(pos - 1);
            return prev.distanceTo(node) + node.distanceTo(depot) - prev.distanceTo(depot);
        }

        // 中间节点：prev -> node -> next 变为 prev -> next
        NodeVO prev = route.get(pos - 1);
        NodeVO next = route.get(pos + 1);
        return prev.distanceTo(node) + node.distanceTo(next) - prev.distanceTo(next);
    }

    /**
     * 尝试在高里程车和低里程车之间交换节点以缩小差距
     * 使用直接计算真实距离的方式（非估算）
     * @return 是否成功交换
     */
    private boolean tryBalanceSwap(SolutionVO solution, VehicleVO maxVehicle, VehicleVO minVehicle,
                                    double maxDist, double minDist) {
        double currentGap = maxDist - minDist;
        double bestGap = currentGap;
        int bestI = -1, bestJ = -1;

        // 遍历高里程车的边缘节点（首尾）和低里程车的全部节点
        List<Integer> maxCandidates = new ArrayList<>();
        if (maxVehicle.getNodeCount() > 0) maxCandidates.add(0);
        if (maxVehicle.getNodeCount() > 1) maxCandidates.add(maxVehicle.getNodeCount() - 1);

        for (int i : maxCandidates) {
            NodeVO n1 = maxVehicle.getRoute().get(i);

            for (int j = 0; j < minVehicle.getNodeCount(); j++) {
                NodeVO n2 = minVehicle.getRoute().get(j);

                // 临时交换，直接计算真实距离
                maxVehicle.getRoute().set(i, n2);
                minVehicle.getRoute().set(j, n1);

                double hNew = maxVehicle.calculateDistance(depot);
                double lNew = minVehicle.calculateDistance(depot);
                double newGap = Math.abs(hNew - lNew);

                if (newGap < bestGap) {
                    bestGap = newGap;
                    bestI = i;
                    bestJ = j;
                }

                // 还原
                maxVehicle.getRoute().set(i, n1);
                minVehicle.getRoute().set(j, n2);
            }
        }

        if (bestI >= 0 && bestJ >= 0) {
            // 执行最佳交换
            NodeVO n1 = maxVehicle.getRoute().get(bestI);
            NodeVO n2 = minVehicle.getRoute().get(bestJ);
            maxVehicle.getRoute().set(bestI, n2);
            minVehicle.getRoute().set(bestJ, n1);
            return true;
        }
        return false;
    }

    // ==================== 兜底微调 ====================

    /**
     * 兜底微调：针对最低里程车的最后补救
     * 从最高里程车的边缘节点中，找到移动后差距缩小的节点，直接移动
     * 不考虑扇形约束，只看里程差距是否缩小
     */
    private void performGapClosing(SolutionVO solution, List<Long> vehicleIds) {
        log.info("========== 执行兜底微调 ==========");

        double targetGapRatio = 0.15;
        int maxIterations = 100;
        int moveCount = 0;

        for (int iter = 0; iter < maxIterations; iter++) {
            // 找最高和最低里程车
            double maxDist = 0, minDist = Double.MAX_VALUE;
            Long maxVId = null, minVId = null;
            double sumDist = 0;

            for (Long vid : vehicleIds) {
                double dist = solution.getVehicle(vid).calculateDistance(depot);
                sumDist += dist;
                if (dist > maxDist) { maxDist = dist; maxVId = vid; }
                if (dist < minDist) { minDist = dist; minVId = vid; }
            }

            double avgDist = sumDist / vehicleIds.size();
            double gapRatio = avgDist > 0 ? (maxDist - minDist) / avgDist : 0;

            if (gapRatio <= targetGapRatio) {
                log.info("兜底微调: 差距比={}% 已达标(目标15%)", String.format("%.1f", gapRatio * 100));
                break;
            }

            VehicleVO maxVehicle = solution.getVehicle(maxVId);
            VehicleVO minVehicle = solution.getVehicle(minVId);

            if (maxVehicle.getNodeCount() <= 1) break;

            // 从最高里程车的边缘节点中找最佳移动
            int bestIdx = -1;
            double bestGap = maxDist - minDist;

            List<Integer> edgeIndices = new ArrayList<>();
            edgeIndices.add(0);
            if (maxVehicle.getNodeCount() > 1) edgeIndices.add(maxVehicle.getNodeCount() - 1);

            for (int i : edgeIndices) {
                NodeVO node = maxVehicle.getRoute().get(i);
                double removalSaving = calculateRemovalSaving(maxVehicle, i);
                double hNew = maxDist - removalSaving;
                double insertionCost = calculateBestInsertionCost(minVehicle, node);
                double lNew = minDist + insertionCost;
                double newGap = Math.abs(hNew - lNew);
                if (newGap < bestGap) {
                    bestGap = newGap;
                    bestIdx = i;
                }
            }

            if (bestIdx < 0) break;

            NodeVO movedNode = maxVehicle.removeNode(bestIdx);
            insertAtBestPosition(minVehicle, movedNode);
            moveCount++;
        }

        // 重新计算评分
        solution.calculateScore(
                config.getWeightDistance(),
                config.getWeightCluster(),
                config.getWeightBalance(),
                config.getWeightGapRatio(),
                refTotalDistance,
                refCluster,
                refBalance,
                refGapRatio
        );

        // 打印最终结果
        StringBuilder sb = new StringBuilder("兜底微调后各车辆里程: ");
        for (Long vid : vehicleIds) {
            sb.append(String.format("%.0f", solution.getVehicle(vid).calculateDistance(depot))).append("km, ");
        }
        log.info(sb.substring(0, sb.length() - 2));
        log.info("兜底微调完成，共移动{}个节点", moveCount);
    }

    // ==================== 顺路捎带优化 ====================

    /**
     * 顺路捎带优化
     * 检查每辆车的路线，如果途经某节点且绕行成本低，则将该节点从其他车辆转移过来
     * 
     * @param solution 解决方案
     * @param vehicleIds 车辆ID列表
     */
    private void performDetourPickup(SolutionVO solution, List<Long> vehicleIds) {
        log.info("========== 执行顺路捎带优化 ==========");
        
        /* 绕行阈值：最大允许的额外距离（公里） - 放宽到50km */
        double detourThreshold = 50.0;
        /* 绕行比例阈值：额外距离占比不超过此值 - 放宽到25% */
        double detourRatioThreshold = 0.25;
        
        int pickupCount = 0;
        boolean changed = true;
        /* 最大迭代次数 */
        int maxIterations = 50;
        int iteration = 0;
        
        while (changed && iteration < maxIterations) {
            changed = false;
            iteration++;
            
            // 按节点数从少到多排序，优先处理节点少的车辆
            List<VehicleVO> sortedVehicles = new ArrayList<>();
            for (Long vid : vehicleIds) {
                sortedVehicles.add(solution.getVehicle(vid));
            }
            sortedVehicles.sort(Comparator.comparingInt(VehicleVO::getNodeCount));
            
            for (VehicleVO vehicle : sortedVehicles) {
                if (vehicle.getNodeCount() == 0) continue;
                
                // 计算当前路线的距离
                double currentRouteDistance = vehicle.calculateDistance(depot);
                
                // 遍历其他车辆（节点数多的优先）
                List<Long> otherVehicleIds = new ArrayList<>(vehicleIds);
                otherVehicleIds.remove(vehicle.getId());
                otherVehicleIds.sort((a, b) -> Integer.compare(
                        solution.getVehicle(b).getNodeCount(), 
                        solution.getVehicle(a).getNodeCount()));
                
                for (Long otherVid : otherVehicleIds) {
                    VehicleVO otherVehicle = solution.getVehicle(otherVid);
                    if (otherVehicle.getNodeCount() <= 2) continue; // 保留至少2个节点
                    
                    // 寻找最佳捎带节点
                    DetourInsertion bestInsertion = findBestDetourInsertion(vehicle, otherVehicle);
                    
                    if (bestInsertion != null && bestInsertion.extraDistance < detourThreshold) {
                        double ratio = currentRouteDistance > 0 ? bestInsertion.extraDistance / currentRouteDistance : 0;
                        
                        // 放宽判断条件：距离阈值或比例阈值任一满足即可
                        if (bestInsertion.extraDistance < detourThreshold || ratio < detourRatioThreshold) {
                            // 执行转移
                            NodeVO nodeToMove = otherVehicle.removeNode(bestInsertion.nodeIndex);
                            vehicle.insertNode(bestInsertion.insertIndex, nodeToMove);
                            
                            log.info("顺路捎带: 节点[{}]从V{}转移到V{}, 额外距离:{}km", 
                                    nodeToMove.getName(), otherVid, vehicle.getId(), 
                                    String.format("%.2f", bestInsertion.extraDistance));
                            
                            pickupCount++;
                            changed = true;
                            break;
                        }
                    }
                }
                if (changed) break;
            }
        }

        // 重新计算评分
        solution.calculateScore(
                config.getWeightDistance(),
                config.getWeightCluster(),
                config.getWeightBalance(),
                config.getWeightGapRatio(),
                refTotalDistance,
                refCluster,
                refBalance,
                refGapRatio
        );

        log.info("顺路捎带完成，共转移 {} 个节点", pickupCount);
    }
    
    /**
     * 寻找最佳顺路捎带插入点
     * 
     * @param vehicle 目标车辆
     * @param otherVehicle 其他车辆（节点来源）
     * @return 最佳插入信息，如果没有合适的返回null
     */
    private DetourInsertion findBestDetourInsertion(VehicleVO vehicle, VehicleVO otherVehicle) {
        DetourInsertion best = null;
        double minExtraDistance = Double.MAX_VALUE;
        
        // 遍历其他车辆的每个节点
        for (int nodeIdx = 0; nodeIdx < otherVehicle.getNodeCount(); nodeIdx++) {
            NodeVO candidateNode = otherVehicle.getRoute().get(nodeIdx);
            
            // 检查该节点是否在目标车辆的扇形区域附近（放宽限制）
            if (!isNodeNearSector(candidateNode, vehicle.getId())) {
                continue;
            }
            
            // 尝试插入到路线的每个位置
            for (int insertPos = 0; insertPos <= vehicle.getNodeCount(); insertPos++) {
                double extraDistance = calculateInsertionCost(vehicle, candidateNode, insertPos);
                
                if (extraDistance < minExtraDistance) {
                    minExtraDistance = extraDistance;
                    best = new DetourInsertion(nodeIdx, insertPos, extraDistance);
                }
            }
        }
        
        return best;
    }
    
    /**
     * 计算在指定位置插入节点的额外距离成本
     */
    private double calculateInsertionCost(VehicleVO vehicle, NodeVO node, int insertPos) {
        List<NodeVO> route = vehicle.getRoute();
        
        if (route.isEmpty()) {
            // 如果路线为空，插入节点的成本是往返距离
            return depot.distanceTo(node) * 2;
        }
        
        double originalSegment;
        double newSegment;
        
        if (insertPos == 0) {
            // 插入到开头：仓库 -> 新节点 -> 原第一个节点
            NodeVO firstNode = route.get(0);
            originalSegment = depot.distanceTo(firstNode);
            newSegment = depot.distanceTo(node) + node.distanceTo(firstNode);
        } else if (insertPos >= route.size()) {
            // 插入到末尾：原最后一个节点 -> 新节点 -> 仓库
            NodeVO lastNode = route.get(route.size() - 1);
            originalSegment = lastNode.distanceTo(depot);
            newSegment = lastNode.distanceTo(node) + node.distanceTo(depot);
        } else {
            // 插入到中间：前节点 -> 新节点 -> 后节点
            NodeVO prevNode = route.get(insertPos - 1);
            NodeVO nextNode = route.get(insertPos);
            originalSegment = prevNode.distanceTo(nextNode);
            newSegment = prevNode.distanceTo(node) + node.distanceTo(nextNode);
        }
        
        return newSegment - originalSegment;
    }
    
    /**
     * 判断节点是否在车辆扇形区域附近（大幅放宽限制）
     * 允许跨区域捎带节点
     */
    private boolean isNodeNearSector(NodeVO node, Long vehicleId) {
        // 顺路捎带时几乎不限制区域，只检查距离
        return true;
    }
    
    /**
     * 顺路捎带插入信息内部类
     */
    private static class DetourInsertion {
        int nodeIndex;      // 在源车辆中的节点索引
        int insertIndex;    // 在目标车辆中的插入位置
        double extraDistance; // 额外距离成本
        
        DetourInsertion(int nodeIndex, int insertIndex, double extraDistance) {
            this.nodeIndex = nodeIndex;
            this.insertIndex = insertIndex;
            this.extraDistance = extraDistance;
        }
    }
}
