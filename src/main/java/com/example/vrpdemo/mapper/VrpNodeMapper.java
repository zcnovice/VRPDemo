package com.example.vrpdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.vrpdemo.entity.VrpNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 节点Mapper接口
 * 提供节点表的数据库操作
 */
@Mapper
public interface VrpNodeMapper extends BaseMapper<VrpNode> {

    /**
     * 查询仓库节点（配送中心）
     * 每个VRP问题必须有一个仓库作为起点和终点
     * 
     * @return 仓库节点，如果没有则返回null
     */
    @Select("SELECT * FROM vrp_node WHERE node_type = 2 AND status = 1 LIMIT 1")
    VrpNode selectDepot();

    /**
     * 查询所有启用的配送点（不包含仓库）
     * 用于获取需要配送的客户位置列表
     * 
     * @return 配送点列表
     */
    @Select("SELECT * FROM vrp_node WHERE node_type = 1 AND status = 1")
    List<VrpNode> selectActiveDeliveryPoints();
}
