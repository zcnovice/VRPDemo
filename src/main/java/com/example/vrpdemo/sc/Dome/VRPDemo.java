package com.example.vrpdemo.sc.Dome;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class VRPDemo {

    // ==============================
// 1. 基础数据模型
// ==============================

    /**
     * 节点类（乡镇或配送中心）
     */
    static class Node {
        String id;
        double x;
        double y;

        public Node(String id, double x, double y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        // 计算两点间直线距离
        public double distanceTo(Node other) {
            return Math.sqrt(Math.pow(this.x - other.x, 2) + Math.pow(this.y - other.y, 2));
        }

        // 计算相对于原点（配送中心）的角度（0-360度）
        public double calculateAngle() {
            double angle = Math.toDegrees(Math.atan2(y, x));
            if (angle < 0) {
                angle += 360;
            }
            return angle;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * 车辆类
     */
    static class Vehicle {
        String id;
        List<Node> route; // 路线列表（不包含起点终点，计算时动态加）

        public Vehicle(String id) {
            this.id = id;
            this.route = new ArrayList<>();
        }

        // 计算该车辆的总里程（从中心出发 -> 访问所有点 -> 回到中心）
        public double calculateDistance(Node depot) {
            if (route.isEmpty()) return 0.0;

            double totalDist = 0.0;
            // 1. 从中心到第一个点
            totalDist += depot.distanceTo(route.get(0));

            // 2. 中间各点
            for (int i = 0; i < route.size() - 1; i++) {
                totalDist += route.get(i).distanceTo(route.get(i + 1));
            }

            // 3. 最后一个点回到中心
            totalDist += route.get(route.size() - 1).distanceTo(depot);

            return totalDist;
        }

        // 深拷贝路线（用于生成新解）
        public List<Node> copyRoute() {
            return new ArrayList<>(route);
        }
    }

    /**
     * 解方案类
     */
    static class Solution {
        Map<String, Vehicle> vehicles; // 车辆ID -> 车辆对象
        double score; // 当前得分

        public Solution(Map<String, Vehicle> vehicles) {
            this.vehicles = vehicles;
        }

        // 深拷贝解（用于生成邻域解）
        public Solution deepCopy() {
            Map<String, Vehicle> newVehicles = new HashMap<>();
            for (Map.Entry<String, Vehicle> entry : this.vehicles.entrySet()) {
                Vehicle v = new Vehicle(entry.getKey());
                v.route = entry.getValue().copyRoute();
                newVehicles.put(v.id, v);
            }
            return new Solution(newVehicles);
        }
    }

    // ==============================
// 2. 算法核心服务
// ==============================

    static class SimulatedAnnealing {
        private Node depot; // 配送中心
        private List<Node> allNodes; // 所有乡镇
        private double initialTemp = 10000.0;
        private double finalTemp = 0.001;
        private double coolingRate = 0.9999; // 降温系数
        // 权重系数
        private double wTotalDist = 0.35; // 总里程权重 50%
        private double wCluster = 0.10;   // 区域聚类权重 30%
        private double wBalance = 0.55;   // 均衡权重 20%
        // 参考值（用于归一化）
        private double refTotalDist; // 总里程参考值
        private double refCluster;   // 聚类评分参考值
        private double refBalance;   // 均衡参考值

        // 扇形区域约束（动态协作式）
        private double baseAngle = 0.0;       // 基础角度（每车主责任区）
        private double overlapRatio = 0.3;   // 协作区比例（30%）
        private Map<String, double[]> vehicleSectors; // 车辆ID -> [主区域起始, 主区域结束, 扩展区起始, 扩展区结束]

        private long startTime; // 添加：记录开始时间
        public SimulatedAnnealing(Node depot, List<Node> allNodes) {
            this.depot = depot;
            this.allNodes = allNodes;
            this.vehicleSectors = new HashMap<>();

            // 估算参考值（用于归一化）
            // 总里程参考值：假设每辆车平均访问10个点，每个点距离约15单位，共20辆车
            refTotalDist = 20 * 10 * 23; // 4600
            // 聚类评分参考值：假设每辆车点到质心的平均距离约10单位
            refCluster = 15;
            // 均衡参考值：假设标准差约50单位
            refBalance = 150;
        }

        /**
         * 计算单辆车的聚类评分（路线点的聚集程度）
         * 方法：计算路线所有点的质心，然后计算每个点到质心的平均距离
         * 距离越小，说明路线点越聚集，评分越好
         */
        private double calculateClusterScore(Vehicle v) {
            if (v.route.isEmpty()) {
                return 0.0;
            }

            // 1. 计算质心（所有点的平均坐标）
            double sumX = 0.0, sumY = 0.0;
            for (Node node : v.route) {
                sumX += node.x;
                sumY += node.y;
            }
            double centerX = sumX / v.route.size();
            double centerY = sumY / v.route.size();

            // 2. 计算每个点到质心的距离，并求平均
            double totalDistToCenter = 0.0;
            for (Node node : v.route) {
                double dist = Math.sqrt(Math.pow(node.x - centerX, 2) + Math.pow(node.y - centerY, 2));
                totalDistToCenter += dist;
            }

            return totalDistToCenter / v.route.size();
        }

        /**
         * 计算所有车辆的总聚类评分
         */
        private double calculateTotalClusterScore(Solution solution) {
            double totalClusterScore = 0.0;
            for (Vehicle v : solution.vehicles.values()) {
                totalClusterScore += calculateClusterScore(v);
            }
            return totalClusterScore;
        }

        /**
         * 判断节点是否在指定车辆的扩展服务区域内
         */
        private boolean isNodeInSector(Node node, String vehicleId) {
            if (!vehicleSectors.containsKey(vehicleId)) {
                return true; // 如果没有分配扇形，允许所有节点
            }

            double[] sector = vehicleSectors.get(vehicleId);
            // sector格式: [主区域起始, 主区域结束, 扩展区起始, 扩展区结束]
            double nodeAngle = node.calculateAngle();
            double extendedMin = sector[2];  // 扩展区起始
            double extendedMax = sector[3];  // 扩展区结束

            // 处理跨越0度的情况（例如：350度到45度）
            if (extendedMin > extendedMax) {
                return nodeAngle >= extendedMin || nodeAngle <= extendedMax;
            } else {
                return nodeAngle >= extendedMin && nodeAngle <= extendedMax;
            }
        }

        /**
         * 计算解的得分 (目标函数 Z)
         * 得分越低越好
         * 多目标优化：总里程(50%) + 区域聚类(30%) + 里程均衡(20%)
         */private double calculateScore(Solution solution) {
            double totalDist = 0.0;
            List<Double> distances = new ArrayList<>();

            // 计算总里程和各车里程
            for (Vehicle v : solution.vehicles.values()) {
                double dist = v.calculateDistance(depot);
                distances.add(dist);
                totalDist += dist;
            }

            // 计算里程方差（用于均衡性评估）
            double avgDist = totalDist / distances.size();
            double variance = 0.0;
            for (double d : distances) {
                variance += Math.pow(d - avgDist, 2);
            }

            // 添加这行：计算标准差
            double stdDev = Math.sqrt(variance);
            // 计算聚类评分（用于区域聚集性评估）
            double clusterScore = calculateTotalClusterScore(solution);

            // 多目标加权函数（归一化后加权求和）
            // 目标函数 = 0.5*(总里程/参考值) + 0.3*(聚类评分/参考值) + 0.2*(方差/参考值)
            double normalizedTotalDist = totalDist / refTotalDist;
            double normalizedCluster = clusterScore / refCluster;
            double normalizedBalance = stdDev / refBalance;

            return wTotalDist * normalizedTotalDist
                    + wCluster * normalizedCluster
                    + wBalance * normalizedBalance;
        }

        /**
         * 初始化解 (按动态协作式扇形区域分配)
         * @param vehicleIds 车辆ID列表
         */
        private Solution createInitialSolution(List<String> vehicleIds) {
            /*  车辆id   车辆信息（里面有节点信息） */
            Map<String, Vehicle> vehicles = new HashMap<>();
            /* 初始化车辆信息 */
            for (String id : vehicleIds) {
                vehicles.put(id, new Vehicle(id));
            }

            // 动态计算扇形角度
            vehicleSectors.clear();
            int numVehicles = vehicleIds.size();
            baseAngle = 360.0 / numVehicles;           // 基础角度（主责任区）
            double overlapAngle = baseAngle * overlapRatio; // 协作扩展角度

            // 为每辆车分配主区域和扩展区
            for (int i = 0; i < numVehicles; i++) {
                double primaryStart = (i * baseAngle) % 360;
                double primaryEnd = ((i + 1) * baseAngle) % 360;
                // 扩展区向两边各扩展 overlapAngle/2
                double extendedStart = (primaryStart - overlapAngle / 2 + 360) % 360;
                double extendedEnd = (primaryEnd + overlapAngle / 2) % 360;

                vehicleSectors.put(vehicleIds.get(i),
                    new double[]{primaryStart, primaryEnd, extendedStart, extendedEnd});
            }

            // 按主责任区优先分配节点（允许溢出到扩展区）
            for (Node node : allNodes) {
                double nodeAngle = node.calculateAngle();
                // 找到该角度对应的主责任区车辆
                int primaryIndex = (int) (nodeAngle / baseAngle);
                primaryIndex = Math.min(primaryIndex, numVehicles - 1); // 确保不越界
                String primaryVehicleId = vehicleIds.get(primaryIndex);

                // 优先分配给主责任区车辆
                vehicles.get(primaryVehicleId).route.add(node);
            }

            // 负载均衡优化：检查各车负载，如果某车节点过多，允许溢出到扩展区内的相邻车辆
            double avgNodesPerVehicle = (double) allNodes.size() / numVehicles;
            double overloadThreshold = avgNodesPerVehicle * 1.5; // 超过平均1.5倍视为过载

            for (int i = 0; i < numVehicles; i++) {
                String vId = vehicleIds.get(i);
                Vehicle v = vehicles.get(vId);

                // 如果该车过载，尝试溢出到相邻车辆（在扩展区内）
                while (v.route.size() > overloadThreshold) {
                    // 找到该车扩展区内的相邻车辆
                    String neighborVehicle = findAdjacentVehicle(vId, vehicleIds, true); // 查找右邻居
                    if (neighborVehicle == null) {
                        neighborVehicle = findAdjacentVehicle(vId, vehicleIds, false); // 查找左邻居
                    }

                    if (neighborVehicle == null) break;

                    // 将边缘节点移动到相邻车辆
                    boolean moved = moveEdgeNodeToNeighbor(v, vehicles.get(neighborVehicle));
                    if (!moved) break;
                }
            }

            Solution sol = new Solution(vehicles);
            sol.score = calculateScore(sol);
            return sol;
        }

        /**
         * 查找指定车辆的相邻车辆
         * @param vehicleId 当前车辆ID
         * @param vehicleIds 所有车辆ID列表
         * @param isRightNeighbor true查找右邻居，false查找左邻居
         * @return 相邻车辆ID，如果无相邻车辆返回null
         */
        private String findAdjacentVehicle(String vehicleId, List<String> vehicleIds, boolean isRightNeighbor) {
            int currentIndex = vehicleIds.indexOf(vehicleId);
            if (currentIndex == -1) return null;

            int neighborIndex = isRightNeighbor ? (currentIndex + 1) % vehicleIds.size()
                                                 : (currentIndex - 1 + vehicleIds.size()) % vehicleIds.size();

            return vehicleIds.get(neighborIndex);
        }

        /**
         * 将过载车辆的边缘节点移动到相邻车辆
         * @param fromVehicle 源车辆
         * @param toVehicle 目标车辆
         * @return 是否成功移动
         */
        private boolean moveEdgeNodeToNeighbor(Vehicle fromVehicle, Vehicle toVehicle) {
            if (fromVehicle.route.isEmpty()) return false;

            // 找到最边缘的节点（角度最小的或最大的）
            Node edgeNode = null;
            int edgeIndex = 0;

            for (int i = 0; i < fromVehicle.route.size(); i++) {
                Node node = fromVehicle.route.get(i);
                double angle = node.calculateAngle();

                // 检查该节点是否在目标车辆的扩展区内
                double[] toSector = vehicleSectors.get(toVehicle.id);
                double extendedMin = toSector[2];
                double extendedMax = toSector[3];

                boolean inExtendedZone;
                if (extendedMin > extendedMax) {
                    inExtendedZone = angle >= extendedMin || angle <= extendedMax;
                } else {
                    inExtendedZone = angle >= extendedMin && angle <= extendedMax;
                }

                if (inExtendedZone) {
                    edgeNode = node;
                    edgeIndex = i;
                    break;
                }
            }

            if (edgeNode == null) return false;

            // 移动节点
            fromVehicle.route.remove(edgeIndex);
            toVehicle.route.add(edgeNode);
            return true;
        }

        /**
         * 邻域搜索 (生成新解)
         * 允许在负载不均衡时跨区域移动节点
         */
        private Solution generateNeighbor(Solution current, List<String> vehicleIds) {
            // 深拷贝当前解
            Solution newSol = current.deepCopy();

            Random rand = new Random();

            // 计算当前各车负载（节点数）和里程
            Map<String, Integer> nodeCounts = new HashMap<>();
            Map<String, Double> distances = new HashMap<>();
            for (String vId : vehicleIds) {
                Vehicle v = newSol.vehicles.get(vId);
                nodeCounts.put(vId, v.route.size());
                distances.put(vId, v.calculateDistance(depot));
            }

            // 计算平均负载和里程
            double avgNodes = nodeCounts.values().stream().mapToInt(Integer::intValue).average().orElse(0);
            double avgDist = distances.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);

            // 随机选两辆车
            String v1Id = vehicleIds.get(rand.nextInt(vehicleIds.size()));
            String v2Id = vehicleIds.get(rand.nextInt(vehicleIds.size()));
            while (v1Id.equals(v2Id)) {
                v2Id = vehicleIds.get(rand.nextInt(vehicleIds.size()));
            }

            Vehicle v1 = newSol.vehicles.get(v1Id);
            Vehicle v2 = newSol.vehicles.get(v2Id);

            // 判断负载是否严重不均衡
            int nodeDiff = Math.abs(v1.route.size() - v2.route.size());
            double dist1 = distances.get(v1Id);
            double dist2 = distances.get(v2Id);
            double maxDist = Math.max(dist1, dist2);
            double minDist = Math.min(dist1, dist2);
            boolean severeImbalance = (nodeDiff > avgNodes * 0.4) || (maxDist > minDist * 2);

            // 根据负载情况选择操作类型
            int moveType;
            if (severeImbalance) {
                // 负载不均衡时，优先进行负载均衡操作
                moveType = rand.nextInt(3); // 0:交换, 1:普通移动, 2:负载均衡移动
            } else {
                moveType = rand.nextInt(2); // 0:交换, 1:普通移动
            }

            try {
                if (moveType == 0 && !v1.route.isEmpty() && !v2.route.isEmpty()) {
                    // 操作1：交换 (两辆车互换一个点)
                    // 放宽条件：如果负载不均衡，允许跨扇形交换
                    List<int[]> validPairs = new ArrayList<>();
                    for (int i = 0; i < v1.route.size(); i++) {
                        for (int j = 0; j < v2.route.size(); j++) {
                            Node n1 = v1.route.get(i);
                            Node n2 = v2.route.get(j);

                            // 正常情况：检查两个节点是否在对方扇形区域内
                            boolean n2InV1 = isNodeInSector(n2, v1Id);
                            boolean n1InV2 = isNodeInSector(n1, v2Id);

                            // 放宽条件：如果严重不均衡，允许交换后能改善均衡性的节点对
                            if (severeImbalance) {
                                // 计算交换后的负载变化
                                boolean canImproveBalance = canExchangeImproveBalance(v1, v2, i, j, avgDist);
                                if (canImproveBalance || (n2InV1 && n1InV2)) {
                                    validPairs.add(new int[]{i, j});
                                }
                            } else {
                                // 正常情况：严格扇形约束
                                if (n2InV1 && n1InV2) {
                                    validPairs.add(new int[]{i, j});
                                }
                            }
                        }
                    }

                    if (!validPairs.isEmpty()) {
                        int[] pair = validPairs.get(rand.nextInt(validPairs.size()));
                        int idx1 = pair[0];
                        int idx2 = pair[1];

                        Node n1 = v1.route.get(idx1);
                        Node n2 = v2.route.get(idx2);

                        v1.route.set(idx1, n2);
                        v2.route.set(idx2, n1);
                    }

                } else if (moveType == 1 && !v1.route.isEmpty()) {
                    // 操作2：普通移动 (把v1的一个点给v2)
                    // 只移动在v2扇形区域内的点（严格约束）
                    List<Integer> validIndices = new ArrayList<>();
                    for (int i = 0; i < v1.route.size(); i++) {
                        Node n = v1.route.get(i);
                        if (isNodeInSector(n, v2Id)) {
                            validIndices.add(i);
                        }
                    }

                    if (!validIndices.isEmpty()) {
                        int idx1 = validIndices.get(rand.nextInt(validIndices.size()));
                        Node n1 = v1.route.remove(idx1);
                        v2.route.add(n1);
                    }

                } else if (moveType == 2 && !v1.route.isEmpty() && severeImbalance) {
                    // 操作3：负载均衡移动（专门针对不均衡情况）
                    // 从高负载车辆向低负载车辆移动节点，放宽扇形约束

                    Vehicle fromVehicle, toVehicle;
                    String fromId, toId;

                    // 确定哪辆车负载更高
                    if (v1.route.size() > v2.route.size() || distances.get(v1Id) > distances.get(v2Id)) {
                        fromVehicle = v1;
                        toVehicle = v2;
                        fromId = v1Id;
                        toId = v2Id;
                    } else {
                        fromVehicle = v2;
                        toVehicle = v1;
                        fromId = v2Id;
                        toId = v1Id;
                    }

                    // 允许移动到扩展区边缘的节点（更宽松的约束）
                    List<Integer> candidates = new ArrayList<>();
                    double[] toSector = vehicleSectors.get(toId);
                    double toExtendedMin = toSector[2];
                    double toExtendedMax = toSector[3];

                    // 进一步扩大搜索范围到相邻扇形
                    double searchRange = baseAngle * (1 + overlapRatio * 2); // 扩大到1.6倍基础角度

                    for (int i = 0; i < fromVehicle.route.size(); i++) {
                        Node n = fromVehicle.route.get(i);
                        double angle = n.calculateAngle();

                        // 计算节点角度与目标车辆扇形中心的距离
                        double sectorCenter = (toExtendedMin + toExtendedMax) / 2;
                        if (toExtendedMin > toExtendedMax) {
                            sectorCenter = (toExtendedMin + (toExtendedMax + 360)) / 2;
                            if (sectorCenter > 360) sectorCenter -= 360;
                        }

                        double angleDiff = Math.abs(angle - sectorCenter);
                        if (angleDiff > 180) angleDiff = 360 - angleDiff;

                        // 如果在搜索范围内，加入候选
                        if (angleDiff <= searchRange / 2) {
                            candidates.add(i);
                        }
                    }

                    if (!candidates.isEmpty()) {
                        int idx = candidates.get(rand.nextInt(candidates.size()));
                        Node n = fromVehicle.route.remove(idx);
                        toVehicle.route.add(n);
                    }
                }
            } catch (Exception e) {
                // 极端情况处理，忽略操作
            }

            newSol.score = calculateScore(newSol);
            return newSol;
        }

        /**
         * 判断交换两个节点是否能改善负载均衡
         */
        private boolean canExchangeImproveBalance(Vehicle v1, Vehicle v2, int idx1, int idx2, double targetAvg) {
            // 计算当前里程差异
            double dist1 = v1.calculateDistance(depot);
            double dist2 = v2.calculateDistance(depot);
            double currentDiff = Math.abs(dist1 - dist2);

            // 模拟交换后的里程（简化计算，使用距离近似）
            Node n1 = v1.route.get(idx1);
            Node n2 = v2.route.get(idx2);

            // 计算节点到各自配送中心的距离
            double n1Dist = depot.distanceTo(n1);
            double n2Dist = depot.distanceTo(n2);

            // 估算交换后的里程变化（简化模型）
            double newDist1 = dist1 - n1Dist + n2Dist;
            double newDist2 = dist2 - n2Dist + n1Dist;
            double newDiff = Math.abs(newDist1 - newDist2);

            // 如果交换后差异减小，则允许
            return newDiff < currentDiff;
        }



        /**
         * 运行算法主流程
         * @Param vehicleIds 车辆ID列表
         */
        public Solution run(List<String> vehicleIds) {
            Random rand = new Random();

            // 记录开始时间
            startTime = System.currentTimeMillis();

            // 1. 初始化
            Solution currentSol = createInitialSolution(vehicleIds);
            Solution bestSol = currentSol;
            double T = initialTemp;

            System.out.println("算法启动... 初始得分: " + bestSol.score);
            System.out.println("--------------------------------------------------------");

            int iteration = 0;

            // 2. 循环降温
            while (T > finalTemp) {
                iteration++;

                // 生成新解
                Solution newSol = generateNeighbor(currentSol, vehicleIds);

                // 计算得分差
                double delta = newSol.score - currentSol.score;

                // Metropolis 接受准则
                if (delta < 0) {
                    // 新解更好，无条件接受
                    currentSol = newSol;
                    if (currentSol.score < bestSol.score) {
                        bestSol = currentSol;
                    }
                } else {
                    // 新解更差，以概率接受
// 公式: P = exp(-delta / T)
                    if (Math.exp(-delta / T) > rand.nextDouble()) {
                        currentSol = newSol;
                    }
                }

                // 降温
                T *= coolingRate;

                // 每50次降温打印一次日志
                if (iteration % 50 == 0) {
                    printLog(iteration, T, bestSol);
                }
            }

            System.out.println("--------------------------------------------------------");
            System.out.println("算法结束！最终结果：");
            printFinalResult(bestSol);

            return bestSol;
        }

        private void printLog(int iter, double T, Solution best) {
            // 简单打印一下当前最优得分
            System.out.printf("迭代: %d, 温度: %.2f, 当前最优得分: %.2f\n", iter, T, best.score);
        }

        private void printFinalResult(Solution sol) {
            /* 计算运行总时间 */            long totalTime = System.currentTimeMillis() - startTime;
            double totalSeconds = totalTime / 1000.0;


            double totalMileage = 0;
            List<Double> dists = new ArrayList<>();
            double totalClusterScore = 0.0;

            for (Map.Entry<String, Vehicle> entry : sol.vehicles.entrySet()) {
                double d = entry.getValue().calculateDistance(depot);
                double cluster = calculateClusterScore(entry.getValue());
                dists.add(d);
                totalMileage += d;
                totalClusterScore += cluster;
                System.out.printf("车辆 [%s]: 访问节点数=%d, 里程=%.2fkm, 聚类度=%.2f\n",
                        entry.getKey(), entry.getValue().route.size(), d, cluster);
            }

            // 计算方差
            double avg = totalMileage / dists.size();
            double variance = 0;
            for(double d : dists) variance += Math.pow(d - avg, 2);
            double avgClusterScore = totalClusterScore / sol.vehicles.size();

            System.out.printf("总里程: %.2fkm, 里程方差(越小越均衡): %.2f\n", totalMileage, variance);
            System.out.printf("平均聚类度: %.2f (越小越好，表示车辆配送区域越集中)\n", avgClusterScore);
            System.out.printf("总运行时间: %.2f秒 (%.1f分钟)\n", totalSeconds, totalSeconds / 60);

            // 导出JSON文件
            exportToJson(sol);
        }

        /**
         * 将结果导出为JSON文件
         */
        private void exportToJson(Solution sol) {
            StringBuilder json = new StringBuilder();
            json.append("{\n");

            // 添加配送中心信息
            json.append("    \"depot\": {\n");
            json.append("        \"id\": \"").append(depot.id).append("\",\n");
            json.append("        \"x\": ").append(depot.x).append(",\n");
            json.append("        \"y\": ").append(depot.y).append("\n");
            json.append("    },\n");

            // 添加车辆信息
            json.append("    \"vehicles\": [\n");
            List<String> vehicleIds = new ArrayList<>(sol.vehicles.keySet());
            for (int i = 0; i < vehicleIds.size(); i++) {
                String vehicleId = vehicleIds.get(i);
                Vehicle v = sol.vehicles.get(vehicleId);
                double distance = v.calculateDistance(depot);

                json.append("        {\n");
                json.append("            \"id\": \"").append(vehicleId).append("\",\n");
                json.append("            \"distance\": ").append(distance).append(",\n");
                json.append("            \"nodes\": [\n");

                // 添加节点信息
                for (int j = 0; j < v.route.size(); j++) {
                    Node node = v.route.get(j);
                    json.append("                {\n");
                    json.append("                    \"id\": \"").append(node.id).append("\",\n");
                    json.append("                    \"x\": ").append(node.x).append(",\n");
                    json.append("                    \"y\": ").append(node.y).append("\n");
                    if (j == v.route.size() - 1) {
                        json.append("                }\n");
                    } else {
                        json.append("                },\n");
                    }
                }

                json.append("            ]\n");
                if (i == vehicleIds.size() - 1) {
                    json.append("        }\n");
                } else {
                    json.append("        },\n");
                }
            }

            json.append("    ]\n");
            json.append("}");

            // 写入文件到指定目录
            String fileName = "D:\\PythonProject\\vrp_result_" + System.currentTimeMillis() + ".json";
            try (FileWriter writer = new FileWriter(fileName)) {
                writer.write(json.toString());
                System.out.println("--------------------------------------------------------");
                System.out.println("JSON文件已生成: " + fileName);
            } catch (IOException e) {
                System.err.println("生成JSON文件失败: " + e.getMessage());
            }
        }
    }

    // ==============================
// 3. 主程序入口
// ==============================
    public static void main(String[] args) {
        // 1. 准备数据
// 配送中心在 (0,0)
        Node depot = new Node("中心", 0, 0);

        // 随机生成200个乡镇
        List<Node> nodes = new ArrayList<>();
        Random rand = new Random();
        for (int i = 1; i <= 200; i++) {
            double x = rand.nextDouble() * 100 - 50; // -50 到 50
            double y = rand.nextDouble() * 100 - 50;
            nodes.add(new Node("乡镇" + i, x, y));
        }

        // 20辆配送车
        List<String> vehicleIds = Arrays.asList(
                "车1", "车2", "车3", "车4", "车5", "车6", "车7", "车8", "车9", "车10",
                "车11", "车12", "车13", "车14", "车15", "车16", "车17", "车18", "车19", "车20"
        );

        // 2. 运行算法
        SimulatedAnnealing sa = new SimulatedAnnealing(depot, nodes);
        Solution run = sa.run(vehicleIds);
    }
}