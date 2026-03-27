package com.example.vrpdemo.sc;

import com.example.vrpdemo.entity.DazhouDeliveryRoute;
import com.example.vrpdemo.mapper.DazhouDeliveryRouteMapper;
import com.example.vrpdemo.mapper.VrpNodeMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 连通性分析工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistanceDiagnostic implements CommandLineRunner {

    private final DazhouDeliveryRouteMapper routeMapper;
    private final VrpNodeMapper nodeMapper;

    @Override
    public void run(String... args) {
        log.info("\n\n========== 连通性分析 ==========\n");
        
        // 1. 加载道路数据
        List<DazhouDeliveryRoute> routes = routeMapper.selectAllRoutes();
        log.info("道路数据总数: {}", routes.size());
        
        // 2. 构建邻接表和坐标信息
        Map<String, Map<String, Double>> adjacencyMap = new HashMap<>();
        Map<String, double[]> nodeCoords = new HashMap<>(); // 坐标缓存
        Set<String> roadNodes = new HashSet<>();
        
        for (DazhouDeliveryRoute route : routes) {
            String start = route.getStartNode();
            String end = route.getEndNode();
            double distance = route.getDistanceKm().doubleValue();
            
            adjacencyMap.computeIfAbsent(start, k -> new HashMap<>()).put(end, distance);
            adjacencyMap.computeIfAbsent(end, k -> new HashMap<>()).put(start, distance);
            
            roadNodes.add(start);
            roadNodes.add(end);
            
            // 缓存坐标
            nodeCoords.put(start, new double[]{route.getStartLng().doubleValue(), route.getStartLat().doubleValue()});
            nodeCoords.put(end, new double[]{route.getEndLng().doubleValue(), route.getEndLat().doubleValue()});
        }
        log.info("道路网络节点数: {}", roadNodes.size());
        
        // 3. 找连通分量
        List<Set<String>> components = findConnectedComponents(adjacencyMap, roadNodes);
        components.sort((a, b) -> Integer.compare(b.size(), a.size()));
        log.info("连通分量数量: {}", components.size());
        
        // 4. 获取仓库
        var depot = nodeMapper.selectDepot();
        String depotName = depot != null ? depot.getName() : null;
        log.info("仓库节点: '{}'", depotName);
        
        // 5. 找仓库所在分量
        int depotComponentIndex = -1;
        if (depotName != null) {
            for (int i = 0; i < components.size(); i++) {
                if (components.get(i).contains(depotName)) {
                    depotComponentIndex = i;
                    break;
                }
            }
        }
        
        Set<String> depotComponent = depotComponentIndex >= 0 ? components.get(depotComponentIndex) : Collections.emptySet();
        
        // 6. 分析配送点
        var deliveryNodes = nodeMapper.selectActiveDeliveryPoints();
        log.info("配送点总数: {}", deliveryNodes.size());
        
        List<String> unreachableList = new ArrayList<>();
        Map<String, Integer> unreachableComponentMap = new HashMap<>(); // 配送点 -> 所在分量索引
        
        for (var node : deliveryNodes) {
            String name = node.getName();
            if (!roadNodes.contains(name)) {
                unreachableList.add(name);
                unreachableComponentMap.put(name, -1); // -1表示不在道路网络中
            } else if (!depotComponent.contains(name)) {
                unreachableList.add(name);
                // 找所在分量
                for (int i = 0; i < components.size(); i++) {
                    if (components.get(i).contains(name)) {
                        unreachableComponentMap.put(name, i);
                        break;
                    }
                }
            }
        }
        
        log.info("\n========== 不可达配送点分析 ==========");
        log.info("从仓库可达: {} 个", deliveryNodes.size() - unreachableList.size());
        log.info("从仓库不可达: {} 个", unreachableList.size());
        
        if (!unreachableList.isEmpty()) {
            log.info("\n不可达配送点列表:");
            for (String name : unreachableList) {
                Integer compIdx = unreachableComponentMap.get(name);
                if (compIdx == null || compIdx == -1) {
                    log.info("  - {} (不在道路网络中)", name);
                } else {
                    log.info("  - {} (在连通分量{})", name, compIdx + 1);
                }
            }
        }
        
        // 7. 计算最短连接方案
        if (!unreachableList.isEmpty() && depotComponentIndex >= 0) {
            log.info("\n========== 最短连接方案 ==========");
            log.info("以下是打通各孤立区域的最短连接SQL：\n");
            
            // 按连通分量分组
            Map<Integer, List<String>> byComponent = new HashMap<>();
            for (String name : unreachableList) {
                Integer compIdx = unreachableComponentMap.get(name);
                byComponent.computeIfAbsent(compIdx, k -> new ArrayList<>()).add(name);
            }
            
            for (Map.Entry<Integer, List<String>> entry : byComponent.entrySet()) {
                int compIdx = entry.getKey();
                List<String> nodesInComp = entry.getValue();
                
                if (compIdx == -1) {
                    log.info("-- 以下配送点不在道路网络中，需要在vrp_node中检查名称匹配：");
                    for (String name : nodesInComp) {
                        log.info("--   {}", name);
                    }
                    continue;
                }
                
                Set<String> otherComponent = components.get(compIdx);
                
                // 找到该分量中与仓库分量最近的节点对
                String bestFrom = null;
                String bestTo = null;
                double minDist = Double.MAX_VALUE;
                
                for (String node1 : otherComponent) {
                    double[] coord1 = nodeCoords.get(node1);
                    if (coord1 == null) continue;
                    
                    for (String node2 : depotComponent) {
                        double[] coord2 = nodeCoords.get(node2);
                        if (coord2 == null) continue;
                        
                        // 欧几里得距离近似
                        double dist = Math.sqrt(Math.pow((coord1[0] - coord2[0]) * 111, 2) + 
                                                Math.pow((coord1[1] - coord2[1]) * 111, 2));
                        if (dist < minDist) {
                            minDist = dist;
                            bestFrom = node1;
                            bestTo = node2;
                        }
                    }
                }
                
                if (bestFrom != null && bestTo != null) {
                    double[] fromCoord = nodeCoords.get(bestFrom);
                    double[] toCoord = nodeCoords.get(bestTo);
                    
                    log.info("-- 连通分量{} (含{}个不可达配送点)", compIdx + 1, nodesInComp.size());
                    log.info("-- 不可达配送点: {}", nodesInComp);
                    log.info("INSERT INTO `dazhou_delivery_routes` (`start_node`, `start_lng`, `start_lat`, `end_node`, `end_lng`, `end_lat`, `distance_km`) VALUES");
                    log.info("('{}', {}, {}, '{}', {}, {}, {});", 
                             bestFrom, fromCoord[0], fromCoord[1],
                             bestTo, toCoord[0], toCoord[1],
                             Math.round(minDist * 10) / 10.0);
                    log.info("");
                }
            }
        }
        
        log.info("\n========== 分析结束 ==========\n");
    }
    
    private List<Set<String>> findConnectedComponents(Map<String, Map<String, Double>> adjacencyMap, 
                                                        Set<String> allNodes) {
        List<Set<String>> components = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        
        for (String node : allNodes) {
            if (visited.contains(node)) continue;
            
            Set<String> component = new HashSet<>();
            Queue<String> queue = new LinkedList<>();
            queue.offer(node);
            visited.add(node);
            
            while (!queue.isEmpty()) {
                String current = queue.poll();
                component.add(current);
                
                Map<String, Double> neighbors = adjacencyMap.get(current);
                if (neighbors != null) {
                    for (String neighbor : neighbors.keySet()) {
                        if (!visited.contains(neighbor)) {
                            visited.add(neighbor);
                            queue.offer(neighbor);
                        }
                    }
                }
            }
            
            components.add(component);
        }
        
        return components;
    }
}
