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
        /* 这个时候每个车都有了对应的节点，并且已经计算了一个初始得分 */

        /* 初始解赋值给最优解 */
        SolutionVO bestSolution = currentSolution;
        log.info("初始解得分: {}", String.format("%.4f", bestSolution.getScore()));

        // 2. 模拟退火主循环
        double temperature = config.getInitialTemperature();
        /* 迭代次数计数器 */
        int iteration = 0;

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
                }
            } else {
                // 新解更差，以概率接受
                double acceptProbability = Math.exp(-delta / temperature);
                if (acceptProbability > Math.random()) {
                    currentSolution = newSolution;
                }
            }

            // 降温
            temperature *= config.getCoolingRate();

            // 日志输出
            if (iteration % 1000 == 0) {
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

        // 6. 计算初始解评分
        solution.calculateScore(
                config.getWeightDistance(),
                config.getWeightCluster(),
                config.getWeightBalance()
        );

        return solution;
    }

    /**
     * 负载均衡优化
     * 检查各车辆负载，如果某车节点过多，溢出到相邻车辆
     */
    private void balanceLoad(SolutionVO solution, List<VehicleVO> vehicles) {
        int numVehicles = vehicles.size();
        /* 计算每个车辆平均节点数 */
        double avgNodesPerVehicle = (double) deliveryNodes.size() / numVehicles;
        /* 过载阈值：平均节点数的1.5倍 */
        double overloadThreshold = avgNodesPerVehicle * 1.5;

        /* 遍历所有车辆获取到id */
        List<Long> vehicleIds = vehicles.stream().map(VehicleVO::getId).toList();

        /* 遍历每一辆车 */
        for (int i = 0; i < numVehicles; i++) {
            Long vId = vehicleIds.get(i);
            VehicleVO vehicle = solution.getVehicle(vId);

            // 如果车辆过载，尝试向邻居溢出
            while (vehicle.getNodeCount() > overloadThreshold) {
                // 查找相邻车辆
                int rightIdx = (i + 1) % numVehicles;
                int leftIdx = (i - 1 + numVehicles) % numVehicles;

                VehicleVO rightNeighbor = solution.getVehicle(vehicleIds.get(rightIdx));
                VehicleVO leftNeighbor = solution.getVehicle(vehicleIds.get(leftIdx));

                // 尝试向右邻居移动节点
                boolean moved = moveEdgeNodeToNeighbor(vehicle, rightNeighbor);
                if (!moved) {
                    // 尝试向左邻居移动节点
                    moved = moveEdgeNodeToNeighbor(vehicle, leftNeighbor);
                }

                if (!moved) break;
            }
        }
    }

    /**
     * 将过载车辆的边缘节点移动到邻居车辆
     * @param fromVehicle 过载车辆
     * @param toVehicle 邻居车辆
     * @return 如果成功移动节点，返回true；否则返回false
     */
    private boolean moveEdgeNodeToNeighbor(VehicleVO fromVehicle, VehicleVO toVehicle) {
        if (fromVehicle.getNodeCount() == 0) {
            return false;
        }

        // 获取目标车辆的扇形区域
        double[] toSector = vehicleSectors.get(toVehicle.getId());
        if (toSector == null) return false;

        /* 扩展区起始, 扩展区结束 */
        double extendedMin = toSector[2];
        double extendedMax = toSector[3];

        /* 遍历这些节点查看是否在扩展区域内，只会找到第一个符合条件的节点 */
        for (int i = 0; i < fromVehicle.getNodeCount(); i++) {
            NodeVO node = fromVehicle.getRoute().get(i);
            /* 获取节点角度 */
            double angle = node.getAngle();

            boolean inExtendedZone;
            if (extendedMin > extendedMax) {
                // 跨越0度的情况
                inExtendedZone = angle >= extendedMin || angle <= extendedMax;
            } else {
                inExtendedZone = angle >= extendedMin && angle <= extendedMax;
            }

            /* 如果节点在扩展区域内，就移动到目标车辆 */
            if (inExtendedZone) {
                fromVehicle.removeNode(i);
                toVehicle.addNode(node);
                return true;
            }
        }

        return false;
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

        /* 节点数量不均衡（超过40%），或者距离不均衡（超过2倍） */
        boolean severeImbalance = (Math.abs(v1.getNodeCount() - v2.getNodeCount()) > avgNodes * 0.4)
                || (Math.max(dist1, dist2) > Math.min(dist1, dist2) * 2);

        // 选择操作类型
        int moveType;
        if (severeImbalance) {
            moveType = rand.nextInt(3); // 0:交换, 1:移动, 2:均衡移动
        } else {
            moveType = rand.nextInt(2); // 0:交换, 1:移动
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
                config.getWeightBalance()
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
                /* 满足交换后里程跟接近 或者 两个节点有共同的扇形区域 */
                if (severeImbalance) {
                    /* 负载不均衡的时候，交换后负载是否均衡 */
                    if (canImproveBalance(v1, v2, i, j) || (n2InV1 && n1InV2)) {
                        validPairs.add(new int[]{i, j});
                    }
                } else {
                    /* 两个节点有共同的扇形区域 */
                    // 正常情况，严格扇形约束
                    if (n2InV1 && n1InV2) {
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

        // 扩大搜索范围
        /* 相比于普通移动（performMove），负载均衡移动会扩大搜索的范围 */
        /* 范围会扩大到车辆1的扇形区域 和 车辆2的扇形区域 的交集 */
        double searchRange = baseAngle * (1 + config.getOverlapRatio() * 2);
        List<Integer> candidates = new ArrayList<>();

        double[] toSector = vehicleSectors.get(toId);
        if (toSector == null) return;

        double sectorCenter = (toSector[2] + toSector[3]) / 2;
        if (toSector[2] > toSector[3]) {
            sectorCenter = (toSector[2] + (toSector[3] + 360)) / 2;
            if (sectorCenter > 360) sectorCenter -= 360;
        }

        for (int i = 0; i < fromVehicle.getNodeCount(); i++) {
            NodeVO node = fromVehicle.getRoute().get(i);
            double angleDiff = Math.abs(node.getAngle() - sectorCenter);
            if (angleDiff > 180) angleDiff = 360 - angleDiff;

            if (angleDiff <= searchRange / 2) {
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
     * 判断节点是否在指定车辆的扇形区域内
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
     * 判断交换是否能改善负载均衡
     * @param v1 车辆1
     * @param v2 车辆2
     * @param idx1 车辆1的节点索引
     * @param idx2 车辆2的节点索引
     */
    private boolean canImproveBalance(VehicleVO v1, VehicleVO v2, int idx1, int idx2) {
        /* 计算当前两辆车到仓库的距离差异 */
        double currentDiff = Math.abs(v1.calculateDistance(depot) - v2.calculateDistance(depot));

        // 简化估算交换后的差异
        /* 获取要交换的两个节点 */
        NodeVO n1 = v1.getRoute().get(idx1);
        NodeVO n2 = v2.getRoute().get(idx2);
        /* 计算这两个节点到仓库的直线距离 */
        double n1Dist = depot.distanceTo(n1);
        double n2Dist = depot.distanceTo(n2);

        /* 计算交换后的距离差异 */
        double newDiff = Math.abs((v1.calculateDistance(depot) - n1Dist + n2Dist)
                - (v2.calculateDistance(depot) - n2Dist + n1Dist));

        /* 如果交换后的距离差异小于当前距离差异，则交换 */
        return newDiff < currentDiff;
    }
}
