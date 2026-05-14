# 模拟退火VRP算法详细解析

## 目录

- [1. 算法总览](#1-算法总览)
- [2. 数据加载阶段](#2-数据加载阶段)
- [3. 构建路网](#3-构建路网)
- [4. 初始解生成](#4-初始解生成)
- [5. 自适应参考值计算](#5-自适应参考值计算)
- [6. 模拟退火主循环](#6-模拟退火主循环)
- [7. 顺路捎带优化](#7-顺路捎带优化)
- [8. 专项均衡优化](#8-专项均衡优化)
- [9. 兜底微调](#9-兜底微调)
- [10. 评分函数详解](#10-评分函数详解)
- [11. 数据结构说明](#11-数据结构说明)

---

## 1. 算法总览

本项目采用**模拟退火算法（Simulated Annealing）**求解车辆路径规划问题（VRP）。算法核心流程分为6个阶段：

```
数据加载 → 初始解生成 → 模拟退火迭代 → 顺路捎带 → 专项均衡 → 兜底微调 → 返回最优解
```

**主入口方法**：`SimulatedAnnealingAlgorithm.solve(NodeVO depot, List<NodeVO> nodes, List<VehicleVO> vehicles)`

```java
// 算法主流程（SimulatedAnnealingAlgorithm.java:62-153）
public SolutionVO solve(NodeVO depot, List<NodeVO> nodes, List<VehicleVO> vehicles) {
    this.depot = depot;
    this.deliveryNodes = nodes;
    this.vehicleSectors = new HashMap<>();

    // 1. 创建初始解
    SolutionVO currentSolution = createInitialSolution(vehicles, nodes);

    // 2. 提取自适应参考值
    this.refTotalDistance = Math.max(currentSolution.getTotalDistance(), 1.0);
    this.refCluster = Math.max(currentSolution.getClusterScore(), 1.0);
    this.refBalance = Math.max(currentSolution.getBalanceScore(), 1.0);

    SolutionVO bestSolution = currentSolution;

    // 3. 模拟退火主循环
    double temperature = config.getInitialTemperature();
    while (temperature > config.getFinalTemperature()) {
        SolutionVO newSolution = generateNeighbor(currentSolution, vehicleIds);
        double delta = newSolution.getScore() - currentSolution.getScore();
        // Metropolis接受准则...
        temperature *= config.getCoolingRate();
    }

    // 4. 后处理优化
    performDetourPickup(bestSolution, vehicleIds);       // 顺路捎带
    performBalanceRefinement(bestSolution, vehicleIds);   // 专项均衡
    performGapClosing(bestSolution, vehicleIds);          // 兜底微调

    return bestSolution;
}
```

---

## 2. 数据加载阶段

对应流程图中的 **Step 1**，数据由 `VrpService` 在调用算法前准备完成。

### 2.1 加载仓库节点（depot）

仓库节点 `NodeVO` 代表配送中心，是所有路线的起点和终点。坐标 `(x, y)` 用作角度计算的参考原点。

```java
// NodeVO.java:136-150
public double calculateAngle(double depotX, double depotY) {
    double dx = this.x - depotX;
    double dy = this.y - depotY;
    this.angle = Math.toDegrees(Math.atan2(dy, dx));
    if (this.angle < 0) {
        this.angle += 360;
    }
    return this.angle;
}
```

### 2.2 加载配送节点（deliveryNodes）

配送节点列表 `List<NodeVO>` 包含所有需要配送的地点。每个节点有：
- `x`, `y`：经纬度坐标
- `name`：乡镇名称（用于匹配道路距离表）
- `demand`：需求量
- `angle`：相对于仓库的角度（运行时计算）

### 2.3 加载车辆列表（vehicles）

车辆列表 `List<VehicleVO>` 包含可用车辆信息：
- `id`：车辆ID
- `vehicleCode`：车辆编码
- `capacity`：最大载重
- `route`：配送路线（初始为空，由算法分配）

### 2.4 加载道路距离表

`DistanceService` 在系统启动时通过 `@PostConstruct` 预计算所有节点对的最短路径：

```java
// DistanceService.java:51-56
@PostConstruct
public void init() {
    loadDistanceCache();
    NodeVO.setDistanceProvider(this);
}
```

数据库表 `dazhou_delivery_routes` 存储了199条双向道路连接，每条记录包含：
- `start_node` / `end_node`：两端乡镇名称
- `distance_km`：道路行驶距离

---

## 3. 构建路网

对应流程图中的 **Step 2**。

### 3.1 邻接表构建

`DistanceService.loadDistanceCache()` 从数据库加载所有路线，构建双向邻接表：

```java
// DistanceService.java:68-81
var routes = routeMapper.selectAllRoutes();
for (var route : routes) {
    String start = route.getStartNode();
    String end = route.getEndNode();
    double distance = route.getDistanceKm().doubleValue();

    // 双向邻接表
    adjacencyMap.computeIfAbsent(start, k -> new HashMap<>()).put(end, distance);
    adjacencyMap.computeIfAbsent(end, k -> new HashMap<>()).put(start, distance);

    allNodes.add(start);
    allNodes.add(end);
}
```

### 3.2 全节点对最短路径预计算

对每个节点执行一次 Dijkstra 算法，缓存所有节点对的最短距离和路径：

```java
// DistanceService.java:97-115
private void precomputeAllPairsShortestPath() {
    for (String source : allNodes) {
        DijkstraResult result = dijkstraAll(source);
        Map<String, Double> distances = result.distances;
        Map<String, String> prev = result.prev;

        for (Map.Entry<String, Double> entry : distances.entrySet()) {
            String target = entry.getKey();
            double distance = entry.getValue();
            String key = source + "|" + target;
            allPairsDistance.put(key, distance);

            if (distance < 999999.0 && !source.equals(target)) {
                allPairsPath.put(key, buildPath(source, target, prev));
            }
        }
    }
}
```

### 3.3 距离查询的 O(1) 查表

算法运行时，`NodeVO.distanceTo()` 通过静态注入的 `DistanceProvider` 查表：

```java
// NodeVO.java:97-112
public double distanceTo(NodeVO other) {
    if (distanceProvider != null && this.name != null && other.name != null) {
        double distance = distanceProvider.getDistance(this.name, other.name);
        if (distance == Double.MAX_VALUE) {
            return 999999.0;
        }
        return distance;
    }
    // 降级为欧几里得距离
    double dx = this.x - other.x;
    double dy = this.y - other.y;
    return Math.sqrt(dx * dx + dy * dy);
}
```

```java
// DistanceService.java:171-184
@Override
public double getDistance(String nodeName1, String nodeName2) {
    if (nodeName1.equals(nodeName2)) return 0.0;
    String key = nodeName1 + "|" + nodeName2;
    return allPairsDistance.getOrDefault(key, 999999.0);
}
```

---

## 4. 初始解生成

对应流程图中的 **Step 3**，方法 `createInitialSolution()`（第165-227行）。

### 4.1 扇形区域划分

将360度按车辆数量等分，每辆车负责一个扇形区域，并向两侧扩展30%重叠区：

```java
// SimulatedAnnealingAlgorithm.java:169-191
baseAngle = 360.0 / numVehicles;                    // 每车基础角度
double overlapAngle = baseAngle * config.getOverlapRatio();  // 重叠角度

for (int i = 0; i < numVehicles; i++) {
    // 主责任区
    double primaryStart = (i * baseAngle) % 360;
    double primaryEnd = ((i + 1) * baseAngle) % 360;

    // 扩展区（向两边扩展overlapAngle/2）
    double extendedStart = (primaryStart - overlapAngle / 2 + 360) % 360;
    double extendedEnd = (primaryEnd + overlapAngle / 2) % 360;

    double[] sector = {primaryStart, primaryEnd, extendedStart, extendedEnd};
    vehicle.setSector(sector);
}
```

**示例**（5辆车，overlapRatio=0.4）：
| 车辆 | 主区域 | 扩展区域（含30%重叠） |
|------|--------|----------------------|
| V1 | 0° ~ 72° | 356.4° ~ 86.4° |
| V2 | 72° ~ 144° | 57.6° ~ 158.4° |
| V3 | 144° ~ 216° | 129.6° ~ 230.4° |
| V4 | 216° ~ 288° | 201.6° ~ 302.4° |
| V5 | 288° ~ 360° | 273.6° ~ 14.4° |

### 4.2 节点角度计算与分配

计算每个配送点相对于仓库的角度，分配到对应扇形：

```java
// SimulatedAnnealingAlgorithm.java:193-211
// 计算所有节点相对于仓库的角度
for (NodeVO node : nodes) {
    node.calculateAngle(depot.getX(), depot.getY());
}

// 按角度分配到对应车辆
for (NodeVO node : nodes) {
    double nodeAngle = node.getAngle();
    int primaryIndex = (int) (nodeAngle / baseAngle);
    primaryIndex = Math.min(primaryIndex, numVehicles - 1);

    VehicleVO vehicle = solution.getVehicle(vehicles.get(primaryIndex).getId());
    if (vehicle != null) {
        vehicle.addNode(node);
    }
}
```

### 4.3 负载均衡（初始解阶段）

初始分配后，各车辆里程可能严重不均。`balanceLoad()` 方法迭代地从高里程车移动节点到低里程车：

```java
// SimulatedAnnealingAlgorithm.java:234-308
private void balanceLoad(SolutionVO solution, List<VehicleVO> vehicles) {
    for (int round = 0; round < 200; round++) {
        // 找最高和最低里程车
        double maxDist = 0, minDist = Double.MAX_VALUE;
        Long maxVId = null, minVId = null;
        for (Long vid : vehicleIds) {
            double dist = solution.getVehicle(vid).calculateDistance(depot);
            if (dist > maxDist) { maxDist = dist; maxVId = vid; }
            if (dist < minDist) { minDist = dist; minVId = vid; }
        }

        double gapRatio = avgDist > 0 ? (maxDist - minDist) / avgDist : 0;
        if (gapRatio <= 0.15) break;  // 目标：差距比≤15%

        // 高里程车节点数不足时找次高
        if (maxVehicle.getNodeCount() <= 3) { /* 找次高里程车 */ }

        // 移动节点（考虑扇形约束和插入成本）
        boolean moved = moveNodeWithDistanceCheck(maxVehicle, minVehicle, true);
        if (!moved) break;
    }
}
```

### 4.4 节点移动的成本计算

`moveNodeWithDistanceCheck()` 在移动节点时，计算插入到目标车辆的最佳位置：

```java
// SimulatedAnnealingAlgorithm.java:357-370
private double calculateBestInsertionCost(VehicleVO vehicle, NodeVO node) {
    if (vehicle.getNodeCount() == 0) {
        return depot.distanceTo(node) * 2;  // 空车：往返距离
    }
    double minCost = Double.MAX_VALUE;
    for (int pos = 0; pos <= vehicle.getNodeCount(); pos++) {
        double cost = calculateInsertionCost(vehicle, node, pos);
        if (cost < minCost) minCost = cost;
    }
    return minCost;
}
```

插入成本计算（三种情况）：

```java
// SimulatedAnnealingAlgorithm.java:1104-1134
private double calculateInsertionCost(VehicleVO vehicle, NodeVO node, int insertPos) {
    if (insertPos == 0) {
        // 插入到开头：仓库→新节点→原首节点
        originalSegment = depot.distanceTo(firstNode);
        newSegment = depot.distanceTo(node) + node.distanceTo(firstNode);
    } else if (insertPos >= route.size()) {
        // 插入到末尾：原尾节点→新节点→仓库
        originalSegment = lastNode.distanceTo(depot);
        newSegment = lastNode.distanceTo(node) + node.distanceTo(depot);
    } else {
        // 插入到中间：前节点→新节点→后节点
        originalSegment = prevNode.distanceTo(nextNode);
        newSegment = prevNode.distanceTo(node) + node.distanceTo(nextNode);
    }
    return newSegment - originalSegment;  // 额外距离 = 新路径 - 原路径
}
```

---

## 5. 自适应参考值计算

对应流程图中的 **Step 4**。

从初始解中提取三个参考值，用于评分归一化：

```java
// SimulatedAnnealingAlgorithm.java:80-82
this.refTotalDistance = Math.max(currentSolution.getTotalDistance(), 1.0);
this.refCluster = Math.max(currentSolution.getClusterScore(), 1.0);
this.refBalance = Math.max(currentSolution.getBalanceScore(), 1.0);
```

**目的**：归一化使三个评分项（总里程、聚类、均衡）在同一量级，权重才能真正反映其重要性。详见[第10节](#10-评分函数详解)。

---

## 6. 模拟退火主循环

对应流程图中的 **Step 5**，核心迭代优化过程。

### 6.1 基本参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `initialTemperature` | 100000.0 | 初始温度，越高探索性越强 |
| `finalTemperature` | 0.01 | 终止温度 |
| `coolingRate` | 0.9999 | 降温系数，越接近1降温越慢 |

**迭代次数估算**：`T_init × coolingRate^n = T_final` → `n = ln(T_final/T_init) / ln(coolingRate)` ≈ 138万次

### 6.2 邻域解生成

每次迭代，随机选择两辆车，执行以下三种操作之一：

```java
// SimulatedAnnealingAlgorithm.java:404-467
private SolutionVO generateNeighbor(SolutionVO current, List<Long> vehicleIds) {
    SolutionVO newSolution = current.deepCopy();  // 深拷贝

    // 随机选两辆车
    VehicleVO v1 = newSolution.getVehicle(vehicleIds.get(idx1));
    VehicleVO v2 = newSolution.getVehicle(vehicleIds.get(idx2));

    // 判断是否严重不均衡
    boolean severeImbalance = (节点数差 > 30%) || (距离差 > 1.3倍);

    // 选择操作类型
    int moveType;
    if (severeImbalance) {
        moveType = rand.nextInt(3);  // 0:交换, 1:移动, 2:均衡移动（各1/3）
    } else {
        if (rand.nextDouble() < 0.5) moveType = 2;  // 50%均衡移动
        else moveType = rand.nextInt(2);              // 50%交换或移动
    }

    switch (moveType) {
        case 0 -> performSwap(v1, v2, severeImbalance);
        case 1 -> performMove(v1, v2);
        case 2 -> performBalanceMove(v1, v2, dist1, dist2);
    }

    newSolution.calculateScore(...);  // 重新计算评分
    return newSolution;
}
```

### 6.3 操作类型详解

#### 操作0：交换（performSwap）

两辆车各交换一个节点。约束条件：
- 两个节点必须在对方的扩展扇形区域内（防止地理漂移）
- 非严重不均衡时，交换后距离差必须缩小

```java
// SimulatedAnnealingAlgorithm.java:473-517
private void performSwap(VehicleVO v1, VehicleVO v2, boolean severeImbalance) {
    List<int[]> validPairs = new ArrayList<>();

    for (int i = 0; i < v1.getNodeCount(); i++) {
        for (int j = 0; j < v2.getNodeCount(); j++) {
            NodeVO n1 = v1.getRoute().get(i);
            NodeVO n2 = v2.getRoute().get(j);

            boolean n2InV1 = isNodeInSector(n2, v1.getId());
            boolean n1InV2 = isNodeInSector(n1, v2.getId());

            if (n2InV1 && n1InV2) {
                if (severeImbalance || canImproveBalance(v1, v2, i, j)) {
                    validPairs.add(new int[]{i, j});
                }
            }
        }
    }

    if (!validPairs.isEmpty()) {
        int[] pair = validPairs.get(rand.nextInt(validPairs.size()));
        // 执行交换
        v1.getRoute().set(pair[0], n2);
        v2.getRoute().set(pair[1], n1);
    }
}
```

#### 操作1：移动（performMove）

将v1的一个节点移动到v2。约束：节点必须在v2的扩展扇形区域内。

```java
// SimulatedAnnealingAlgorithm.java:523-547
private void performMove(VehicleVO v1, VehicleVO v2) {
    List<Integer> validIndices = new ArrayList<>();
    for (int i = 0; i < v1.getNodeCount(); i++) {
        if (isNodeInSector(v1.getRoute().get(i), v2.getId())) {
            validIndices.add(i);
        }
    }
    if (!validIndices.isEmpty()) {
        int idx = validIndices.get(rand.nextInt(validIndices.size()));
        NodeVO node = v1.removeNode(idx);
        v2.addNode(node);
    }
}
```

#### 操作2：均衡移动（performBalanceMove）

从高负载车向低负载车移动节点。判断高负载的依据：节点数更多**或**里程更远。

```java
// SimulatedAnnealingAlgorithm.java:557-596
private void performBalanceMove(VehicleVO v1, VehicleVO v2, double dist1, double dist2) {
    // 确定高负载车
    if (v1.getNodeCount() > v2.getNodeCount() || dist1 > dist2) {
        fromVehicle = v1; toVehicle = v2;
    } else {
        fromVehicle = v2; toVehicle = v1;
    }

    // 在高负载车中找目标车扇形内的节点
    List<Integer> candidates = new ArrayList<>();
    for (int i = 0; i < fromVehicle.getNodeCount(); i++) {
        if (isNodeInSector(fromVehicle.getRoute().get(i), toId)) {
            candidates.add(i);
        }
    }
    // 随机选一个移动
    if (!candidates.isEmpty()) {
        int idx = candidates.get(rand.nextInt(candidates.size()));
        NodeVO node = fromVehicle.removeNode(idx);
        toVehicle.addNode(node);
    }
}
```

### 6.4 扇形区域判断

```java
// SimulatedAnnealingAlgorithm.java:602-617
private boolean isNodeInSector(NodeVO node, Long vehicleId) {
    double[] sector = vehicleSectors.get(vehicleId);
    double nodeAngle = node.getAngle();
    double extendedMin = sector[2];  // 扩展区起始
    double extendedMax = sector[3];  // 扩展区结束

    // 处理跨0°的情况（如356°~14°）
    if (extendedMin > extendedMax) {
        return nodeAngle >= extendedMin || nodeAngle <= extendedMax;
    } else {
        return nodeAngle >= extendedMin && nodeAngle <= extendedMax;
    }
}
```

### 6.5 Metropolis 接受准则

```java
// SimulatedAnnealingAlgorithm.java:104-119
double delta = newSolution.getScore() - currentSolution.getScore();

if (delta < 0) {
    // 新解更好，无条件接受
    currentSolution = newSolution;
    if (currentSolution.getScore() < bestSolution.getScore()) {
        bestSolution = currentSolution;
    }
} else {
    // 新解更差，以概率 exp(-delta/T) 接受
    double acceptProbability = Math.exp(-delta / temperature);
    if (acceptProbability > Math.random()) {
        currentSolution = newSolution;
    }
}

// 降温
temperature *= config.getCoolingRate();  // 0.9999
```

**关键设计**：
- 温度高时，接受差解的概率大 → 广泛探索
- 温度低时，几乎只接受好解 → 精细收敛
- 始终保留全局最优解 `bestSolution`

---

## 7. 顺路捎带优化

对应流程图中的 **Step 6**，在模拟退火结束后执行。

### 7.1 核心思想

如果车辆A的路线途经某节点附近，而该节点当前分配给车辆B，且绕行成本很低，则将该节点转移到车辆A。这样可以减少总里程。

### 7.2 算法流程

```java
// SimulatedAnnealingAlgorithm.java:986-1065
private void performDetourPickup(SolutionVO solution, List<Long> vehicleIds) {
    double detourThreshold = 50.0;      // 绕行阈值：50km
    double detourRatioThreshold = 0.25; // 绕行比例：25%

    while (changed && iteration < 50) {
        changed = false;

        // 按节点数从少到多排序（优先处理节点少的车）
        sortedVehicles.sort(Comparator.comparingInt(VehicleVO::getNodeCount));

        for (VehicleVO vehicle : sortedVehicles) {
            // 遍历其他车辆（节点数多的优先）
            for (Long otherVid : otherVehicleIds) {
                if (otherVehicle.getNodeCount() <= 2) continue;  // 保留至少2个节点

                // 寻找最佳捎带节点
                DetourInsertion bestInsertion = findBestDetourInsertion(vehicle, otherVehicle);

                if (bestInsertion != null && bestInsertion.extraDistance < detourThreshold) {
                    // 执行转移
                    NodeVO nodeToMove = otherVehicle.removeNode(bestInsertion.nodeIndex);
                    vehicle.insertNode(bestInsertion.insertIndex, nodeToMove);
                    changed = true;
                    break;
                }
            }
        }
    }
}
```

### 7.3 最佳捎带点查找

遍历源车辆的每个节点，尝试插入到目标车辆的每个位置，找到额外距离最小的组合：

```java
// SimulatedAnnealingAlgorithm.java:1074-1099
private DetourInsertion findBestDetourInsertion(VehicleVO vehicle, VehicleVO otherVehicle) {
    DetourInsertion best = null;
    double minExtraDistance = Double.MAX_VALUE;

    for (int nodeIdx = 0; nodeIdx < otherVehicle.getNodeCount(); nodeIdx++) {
        NodeVO candidateNode = otherVehicle.getRoute().get(nodeIdx);

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
```

**注意**：`isNodeNearSector()` 当前无条件返回 `true`，即不限制地理区域，完全靠距离阈值控制。

---

## 8. 专项均衡优化

对应流程图中的 **Step 7**，在顺路捎带之后执行。

### 8.1 目标

将各车辆的最大最小里程差距控制在 **10%** 以内。

### 8.2 总里程约束

为防止均衡优化导致总里程大幅增加，设置总里程上限：

```java
// SimulatedAnnealingAlgorithm.java:668-672
double baselineTotalDist = 0;
for (Long vid : vehicleIds) {
    baselineTotalDist += solution.getVehicle(vid).calculateDistance(depot);
}
double maxAllowedDist = baselineTotalDist * 1.10;  // 最多增加10%
```

### 8.3 移动策略

每轮迭代：
1. 找到最高里程车和最低里程车
2. 遍历最高车的每个节点，尝试插入到每辆其他车
3. 计算移动后的**全局**最大最小差距（不仅仅是这两辆车）
4. 选择全局差距最小的移动方案
5. 如果移动无法改善，尝试交换

```java
// SimulatedAnnealingAlgorithm.java:719-757
for (int i = 0; i < maxVehicle.getNodeCount(); i++) {
    NodeVO node = maxVehicle.getRoute().get(i);
    double removalSaving = calculateRemovalSaving(maxVehicle, i);
    double hNew = maxDist - removalSaving;

    for (Long targetVId : vehicleIds) {
        if (targetVId.equals(maxVId)) continue;

        double insertionCost = calculateBestInsertionCost(targetVehicle, node);
        double tNew = targetDist + insertionCost;
        double newTotalDist = sumDist - removalSaving + insertionCost;

        if (newTotalDist > maxAllowedDist) continue;  // 总里程约束

        // 计算移动后全局最大最小差距
        double newGap = ...;
        if (newGap < bestGlobalGap) {
            bestGlobalGap = newGap;
            bestNodeIdx = i;
            bestTargetVId = targetVId;
        }
    }
}
```

### 8.4 移除节省计算

```java
// SimulatedAnnealingAlgorithm.java:810-835
private double calculateRemovalSaving(VehicleVO vehicle, int pos) {
    if (route.size() == 1) {
        return depot.distanceTo(node) * 2;  // 只有一个节点
    }
    if (pos == 0) {
        // 首节点：depot→node→next 变为 depot→next
        return depot.distanceTo(node) + node.distanceTo(next) - depot.distanceTo(next);
    }
    if (pos == route.size() - 1) {
        // 尾节点：prev→node→depot 变为 prev→depot
        return prev.distanceTo(node) + node.distanceTo(depot) - prev.distanceTo(depot);
    }
    // 中间节点：prev→node→next 变为 prev→next
    return prev.distanceTo(node) + node.distanceTo(next) - prev.distanceTo(next);
}
```

### 8.5 交换策略（tryBalanceSwap）

当移动无法改善时，尝试交换最高车的**边缘节点**（首尾）与最低车的任意节点：

```java
// SimulatedAnnealingAlgorithm.java:842-888
private boolean tryBalanceSwap(SolutionVO solution, VehicleVO maxVehicle, VehicleVO minVehicle, ...) {
    // 高里程车只考虑边缘节点（首尾）
    List<Integer> maxCandidates = new ArrayList<>();
    if (maxVehicle.getNodeCount() > 0) maxCandidates.add(0);
    if (maxVehicle.getNodeCount() > 1) maxCandidates.add(maxVehicle.getNodeCount() - 1);

    for (int i : maxCandidates) {
        for (int j = 0; j < minVehicle.getNodeCount(); j++) {
            // 临时交换，直接计算真实距离
            maxVehicle.getRoute().set(i, n2);
            minVehicle.getRoute().set(j, n1);

            double newGap = Math.abs(hNew - lNew);
            if (newGap < bestGap) { /* 记录最佳 */ }

            // 还原
            maxVehicle.getRoute().set(i, n1);
            minVehicle.getRoute().set(j, n2);
        }
    }
    // 执行最佳交换
}
```

---

## 9. 兜底微调

对应流程图中的 **Step 8**，最后的补救措施。

### 9.1 目标

如果专项均衡后差距比仍超过 **15%**，从最高车的边缘节点直接移动到最低车。

### 9.2 算法

```java
// SimulatedAnnealingAlgorithm.java:897-975
private void performGapClosing(SolutionVO solution, List<Long> vehicleIds) {
    double targetGapRatio = 0.15;

    for (int iter = 0; iter < 100; iter++) {
        // 找最高和最低里程车
        if (gapRatio <= targetGapRatio) break;
        if (maxVehicle.getNodeCount() <= 1) break;

        // 只考虑最高车的边缘节点（首尾）
        List<Integer> edgeIndices = new ArrayList<>();
        edgeIndices.add(0);
        if (maxVehicle.getNodeCount() > 1) edgeIndices.add(maxVehicle.getNodeCount() - 1);

        for (int i : edgeIndices) {
            double removalSaving = calculateRemovalSaving(maxVehicle, i);
            double hNew = maxDist - removalSaving;
            double insertionCost = calculateBestInsertionCost(minVehicle, node);
            double lNew = minDist + insertionCost;
            double newGap = Math.abs(hNew - lNew);
            if (newGap < bestGap) { /* 记录最佳 */ }
        }

        // 执行移动
        NodeVO movedNode = maxVehicle.removeNode(bestIdx);
        insertAtBestPosition(minVehicle, movedNode);
    }
}
```

**与专项均衡的区别**：兜底微调不考虑总里程约束，不做全局搜索，只在最高车和最低车之间移动边缘节点，追求快速收敛。

---

## 10. 评分函数详解

### 10.1 综合评分公式

```java
// SolutionVO.java:100-119
score = weightDistance × (totalDistance / refTotalDistance)
      + weightCluster × (clusterScore / refCluster)
      + weightBalance × (balanceScore / refBalance)
```

### 10.2 三项评分

| 评分项 | 计算方法 | 目标 | 权重 |
|--------|----------|------|------|
| 总里程 `totalDistance` | 所有车辆行驶距离之和（基于道路距离） | 越小越好 | 0.15 |
| 聚类评分 `clusterScore` | 各车路线节点到质心的平均距离之和 | 越小越好（路线越紧凑） | 0.05 |
| 均衡评分 `balanceScore` | (变异系数 + 差距惩罚) × 平均里程 | 越小越好（各车均衡） | 0.80 |

### 10.3 均衡评分详解

```java
// SolutionVO.java:171-215
private void calculateBalanceScore() {
    // 变异系数 = 标准差 / 平均值
    double cv = stdDev / avg;

    // 最大最小差距比
    double gapRatio = (maxDist - minDist) / avg;

    // 超过10%的部分施加5倍惩罚
    double penalty = 5.0 * Math.max(0, gapRatio - 0.10);

    // 乘以平均里程，使评分与总里程同尺度
    this.balanceScore = (cv + penalty) * avg;
}
```

**设计意图**：均衡权重高达0.80，说明本算法优先保证各车辆里程均衡，其次才是总里程最小。

---

## 11. 数据结构说明

### 11.1 NodeVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 数据库主键 |
| `nodeCode` | String | 节点编码 |
| `name` | String | 乡镇名称（匹配道路距离表） |
| `x`, `y` | double | 经纬度坐标 |
| `demand` | double | 需求量 |
| `angle` | double | 相对于仓库的角度（0-360°） |
| `distanceProvider` | static | 距离提供者（由DistanceService注入） |

### 11.2 VehicleVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 车辆ID |
| `route` | List<NodeVO> | 配送路线（节点访问顺序） |
| `capacity` | double | 最大载重 |
| `currentLoad` | double | 当前载重 |
| `sector` | double[4] | 扇形区域 [主起, 主止, 扩起, 扩止] |

### 11.3 SolutionVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `vehicleMap` | Map<Long, VehicleVO> | 车辆ID→车辆对象映射 |
| `totalDistance` | double | 总行驶距离 |
| `clusterScore` | double | 聚类评分 |
| `balanceScore` | double | 均衡评分 |
| `score` | double | 综合得分（越低越好） |
| `depot` | NodeVO | 仓库节点 |

### 11.4 VrpAlgorithmConfig

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `initialTemperature` | 100000.0 | 初始温度 |
| `finalTemperature` | 0.01 | 终止温度 |
| `coolingRate` | 0.9999 | 降温系数 |
| `weightDistance` | 0.15 | 总里程权重 |
| `weightCluster` | 0.05 | 聚类权重 |
| `weightBalance` | 0.80 | 均衡权重 |
| `overlapRatio` | 0.4 | 扇形重叠比例 |

---

## 附录：方法调用关系图

```
solve()
├── createInitialSolution()
│   ├── 计算扇形区域
│   ├── calculateAngle()        // NodeVO
│   ├── 按角度分配节点
│   └── balanceLoad()
│       └── moveNodeWithDistanceCheck()
│           ├── isNodeInSector()
│           ├── calculateBestInsertionCost()
│           └── insertAtBestPosition()
│
├── [模拟退火循环]
│   └── generateNeighbor()
│       ├── deepCopy()          // SolutionVO
│       ├── performSwap()
│       │   ├── isNodeInSector()
│       │   └── canImproveBalance()
│       │       ├── calculateRemovalSaving()
│       │       └── calculateBestInsertionCost()
│       ├── performMove()
│       │   └── isNodeInSector()
│       └── performBalanceMove()
│           └── isNodeInSector()
│
├── performDetourPickup()
│   ├── findBestDetourInsertion()
│   │   ├── isNodeNearSector()
│   │   └── calculateInsertionCost()
│   └── insertNode()            // VehicleVO
│
├── performBalanceRefinement()
│   ├── calculateRemovalSaving()
│   ├── calculateBestInsertionCost()
│   ├── insertAtBestPosition()
│   └── tryBalanceSwap()
│       └── calculateDistance()  // VehicleVO
│
└── performGapClosing()
    ├── calculateRemovalSaving()
    ├── calculateBestInsertionCost()
    └── insertAtBestPosition()
```
