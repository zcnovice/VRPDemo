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
     * 对每个节点执行一次Dijkstra，缓存到所有其他节点的最短距离
     */
    private void precomputeAllPairsShortestPath() {
        for (String source : allNodes) {
            /* dijkstraAll -- Dijkstra算法获取最短路径 */
            Map<String, Double> distances = dijkstraAll(source);
            for (Map.Entry<String, Double> entry : distances.entrySet()) {
                /* 终点名称 */
                String target = entry.getKey();
                /* 最短距离 */
                double distance = entry.getValue();
                // 存储: "from|to" -> distance
                allPairsDistance.put(source + "|" + target, distance);
            }
        }
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
     * Dijkstra算法：计算从起点到所有其他节点的最短距离
     * 
     * @param start 起点
     * @return 到所有节点的最短距离Map
     */
    private Map<String, Double> dijkstraAll(String start) {
        Map<String, Double> distances = new HashMap<>();
        Set<String> visited = new HashSet<>();
        PriorityQueue<NodeDistance> pq = new PriorityQueue<>(
                Comparator.comparingDouble(NodeDistance::getDistance));

        // 初始化
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
                    pq.offer(new NodeDistance(neighbor, newDistance));
                }
            }
        }

        return distances;
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
