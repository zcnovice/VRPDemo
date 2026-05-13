package com.example.vrpdemo.service;

import com.example.vrpdemo.mapper.DazhouDeliveryRouteMapper;
import com.example.vrpdemo.vo.NodeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * 距离服务
 * 管理节点间的实际道路距离
 * 启动时预计算所有节点对的最短路径，运行时O(1)查表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistanceService implements NodeVO.DistanceProvider {

    /*  */
    private final DazhouDeliveryRouteMapper routeMapper;

    /**
     * 邻接表：节点 -> (邻居节点 -> 距离)
     */
    private Map<String, Map<String, Double>> adjacencyMap = new HashMap<>();

    /**
     * 所有节点名称
     */
    private Set<String> allNodes = new HashSet<>();

    /**
     * 预计算的所有节点对最短距离缓存
     * key: "fromNode|toNode", value: shortestDistance
     */
    private Map<String, Double> allPairsDistance = new HashMap<>();

    /**
     * 预计算的所有节点对最短路径缓存
     * key: "fromNode|toNode", value: 途径节点列表（包含起点和终点）
     */
    private Map<String, List<String>> allPairsPath = new HashMap<>();

    /**
     * 初始化加载所有距离数据到缓存
     */
    /* @PostConstruct系统启动时执行一次 */
    @PostConstruct
    public void init() {
        loadDistanceCache();
        // 将自己注入到NodeVO中
        NodeVO.setDistanceProvider(this);
    }

    /**
     * 从数据库加载所有距离数据到内存缓存
     * 并预计算所有节点对的最短路径
     */
    public void loadDistanceCache() {
        adjacencyMap.clear();
        allNodes.clear();
        allPairsDistance.clear();
        allPairsPath.clear();

        // 1. 加载道路数据，构建邻接表
        var routes = routeMapper.selectAllRoutes();
        for (var route : routes) {
            String start = route.getStartNode();
            String end = route.getEndNode();
            double distance = route.getDistanceKm().doubleValue();

            // 构建邻接表（双向）
            adjacencyMap.computeIfAbsent(start, k -> new HashMap<>()).put(end, distance);
            adjacencyMap.computeIfAbsent(end, k -> new HashMap<>()).put(start, distance);

            allNodes.add(start);
            allNodes.add(end);
        }

        log.info("加载道路距离数据完成: 路线数量={}, 节点数={}", routes.size(), allNodes.size());

        // 2. 预计算所有节点对的最短路径
        long startTime = System.currentTimeMillis();
        precomputeAllPairsShortestPath();
        long elapsed = System.currentTimeMillis() - startTime;

        log.info("预计算所有节点对最短路径完成: 节点对数量={}, 耗时={}ms", allPairsDistance.size(), elapsed);
    }

    /**
     * 预计算所有节点对的最短路径
     * 对每个节点执行一次Dijkstra，缓存到所有其他节点的最短距离和路径
     */
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

                // 构建路径
                if (distance < 999999.0 && !source.equals(target)) {
                    allPairsPath.put(key, buildPath(source, target, prev));
                }
            }
        }
    }

    /**
     * 根据前驱节点表回溯构建路径
     */
    private List<String> buildPath(String source, String target, Map<String, String> prev) {
        LinkedList<String> path = new LinkedList<>();
        String current = target;
        while (current != null) {
            path.addFirst(current);
            current = prev.get(current);
        }
        return path;
    }
    
    /**
     * 打印所有节点到指定仓库的最短距离
     * 由外部调用，在加载仓库后执行
     * 
     * @param depotName 仓库名称
     */
    public void printDistancesToDepot(String depotName) {
        log.info("尝试打印距离: depotName={}, allNodes包含该节点={}", depotName, allNodes.contains(depotName));
        
        if (depotName == null) {
            log.warn("仓库名称为空，无法打印距离");
            return;
        }
        
        if (!allNodes.contains(depotName)) {
            log.warn("仓库节点[{}]不存在于道路距离表中，可用节点: {}", depotName, allNodes);
            return;
        }
        
        log.info("========== 各节点到仓库[{}]的最短距离 ==========", depotName);
        List<Map.Entry<String, Double>> sortedDistances = new ArrayList<>();
        
        for (String node : allNodes) {
            if (!node.equals(depotName)) {
                String key = depotName + "|" + node;
                Double dist = allPairsDistance.get(key);
                if (dist != null && dist < 999999.0) {
                    sortedDistances.add(Map.entry(node, dist));
                }
            }
        }
        
        // 按距离排序
        sortedDistances.sort(Comparator.comparingDouble(Map.Entry::getValue));
        for (Map.Entry<String, Double> entry : sortedDistances) {
            log.info("  {} -> {}: {} km", depotName, entry.getKey(), String.format("%.2f", entry.getValue()));
        }
        log.info("================================================");
    }

    @Override
    public double getDistance(String nodeName1, String nodeName2) {
        if (nodeName1 == null || nodeName2 == null) {
            return 999999.0;
        }

        if (nodeName1.equals(nodeName2)) {
            return 0.0;
        }

        // O(1) 查表获取预计算的距离
        String key = nodeName1 + "|" + nodeName2;
        /* 如果缓存中没有该节点对的距离，返回999999.0 */
        return allPairsDistance.getOrDefault(key, 999999.0);
    }

    @Override
    public boolean isReachable(String nodeName1, String nodeName2) {
        return getDistance(nodeName1, nodeName2) < 999999.0;
    }

    /**
     * 获取两点之间的最短路径（途径节点列表）
     *
     * @param from 起点
     * @param to   终点
     * @return 途径节点列表（包含起点和终点），不可达时返回空列表
     */
    public List<String> getShortestPath(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return Collections.emptyList();
        }
        String key = from + "|" + to;
        return allPairsPath.getOrDefault(key, Collections.emptyList());
    }

    /**
     * 获取两点之间的最短路径，包含每段距离
     *
     * @param from 起点
     * @param to   终点
     * @return 路径段列表，每段包含节点名和到下一节点的距离
     */
    public List<Map<String, Object>> getShortestPathWithDistance(String from, String to) {
        List<String> path = getShortestPath(from, to);
        if (path.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> segments = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            Map<String, Object> seg = new HashMap<>();
            seg.put("node", path.get(i));
            if (i < path.size() - 1) {
                // 从邻接表获取相邻节点间的直接距离
                Double dist = adjacencyMap.getOrDefault(path.get(i), Collections.emptyMap())
                        .get(path.get(i + 1));
                seg.put("distanceToNext", dist != null ? dist : 0.0);
            }
            segments.add(seg);
        }
        return segments;
    }

    /**
     * Dijkstra算法：计算从起点到所有其他节点的最短距离和路径
     *
     * @param start 起点
     * @return 包含距离和前驱节点的结果
     */
    private DijkstraResult dijkstraAll(String start) {
        Map<String, Double> distances = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Set<String> visited = new HashSet<>();
        PriorityQueue<NodeDistance> pq = new PriorityQueue<>(
                Comparator.comparingDouble(NodeDistance::getDistance));

        for (String node : allNodes) {
            distances.put(node, 999999.0);
        }
        distances.put(start, 0.0);
        pq.offer(new NodeDistance(start, 0.0));

        while (!pq.isEmpty()) {
            NodeDistance current = pq.poll();
            String currentNode = current.node;

            if (visited.contains(currentNode)) {
                continue;
            }
            visited.add(currentNode);

            Map<String, Double> neighbors = adjacencyMap.get(currentNode);
            if (neighbors == null) continue;

            for (Map.Entry<String, Double> entry : neighbors.entrySet()) {
                String neighbor = entry.getKey();
                double edgeDistance = entry.getValue();

                if (visited.contains(neighbor)) continue;

                double newDistance = distances.get(currentNode) + edgeDistance;
                if (newDistance < distances.get(neighbor)) {
                    distances.put(neighbor, newDistance);
                    prev.put(neighbor, currentNode);
                    pq.offer(new NodeDistance(neighbor, newDistance));
                }
            }
        }
        return new DijkstraResult(distances, prev);
    }

    /**
     * 内部类：Dijkstra算法结果
     */
    private static class DijkstraResult {
        final Map<String, Double> distances;
        final Map<String, String> prev;

        DijkstraResult(Map<String, Double> distances, Map<String, String> prev) {
            this.distances = distances;
            this.prev = prev;
        }
    }

    /**
     * 获取节点的所有直接邻居节点
     * 
     * @param nodeName 节点名称
     * @return 可到达的邻居节点集合
     */
    public Set<String> getNeighbors(String nodeName) {
        Map<String, Double> neighbors = adjacencyMap.get(nodeName);
        return neighbors != null ? neighbors.keySet() : Collections.emptySet();
    }

    /**
     * 检查节点是否存在
     * 
     * @param nodeName 节点名称
     * @return true-存在，false-不存在
     */
    public boolean nodeExists(String nodeName) {
        return allNodes.contains(nodeName);
    }

    /**
     * 获取所有节点名称
     */
    public Set<String> getAllNodeNames() {
        return allNodes;
    }

    /**
     * 内部类：节点距离对
     */
    private static class NodeDistance {
        String node;
        double distance;

        NodeDistance(String node, double distance) {
            this.node = node;
            this.distance = distance;
        }

        double getDistance() {
            return distance;
        }
    }
}
