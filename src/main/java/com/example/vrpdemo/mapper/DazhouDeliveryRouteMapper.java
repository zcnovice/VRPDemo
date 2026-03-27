package com.example.vrpdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.vrpdemo.entity.DazhouDeliveryRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 达州配送路线Mapper接口
 * 提供道路距离查询
 */
@Mapper
public interface DazhouDeliveryRouteMapper extends BaseMapper<DazhouDeliveryRoute> {

    /**
     * 查询两点之间的道路距离
     * 支持双向查询（A->B 或 B->A）
     * 
     * @param startNode 起点名称
     * @param endNode 终点名称
     * @return 道路距离（公里），如果不存在则返回null
     */
    @Select("SELECT distance_km FROM dazhou_delivery_routes " +
            "WHERE (start_node = #{startNode} AND end_node = #{endNode}) " +
            "OR (start_node = #{endNode} AND end_node = #{startNode}) " +
            "LIMIT 1")
    Double selectDistance(@Param("startNode") String startNode, @Param("endNode") String endNode);

    /**
     * 查询所有路线数据
     */
    @Select("SELECT * FROM dazhou_delivery_routes")
    List<DazhouDeliveryRoute> selectAllRoutes();

    /**
     * 查询从指定节点可以到达的所有邻居节点
     * 
     * @param nodeName 节点名称
     * @return 可到达的路线列表
     */
    @Select("SELECT * FROM dazhou_delivery_routes WHERE start_node = #{nodeName} OR end_node = #{nodeName}")
    List<DazhouDeliveryRoute> selectNeighbors(@Param("nodeName") String nodeName);
}
