package com.example.vrpdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.vrpdemo.entity.VrpVehicle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 车辆Mapper接口
 * 提供车辆表的数据库操作
 */
@Mapper
public interface VrpVehicleMapper extends BaseMapper<VrpVehicle> {

    /**
     * 查询指定数量的可用车辆
     * 用于获取参与配送的车辆列表
     * 
     * @param limit 需要的车辆数量
     * @return 可用车辆列表
     */
    @Select("SELECT * FROM vrp_vehicle WHERE status = 1 ORDER BY id LIMIT #{limit}")
    List<VrpVehicle> selectAvailableVehicles(@Param("limit") int limit);

    /**
     * 查询所有可用车辆
     * 
     * @return 所有启用状态的车辆列表
     */
    @Select("SELECT * FROM vrp_vehicle WHERE status = 1 ORDER BY id")
    List<VrpVehicle> selectAllAvailable();
}
