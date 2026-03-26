package com.example.vrpdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.vrpdemo.entity.VrpRouteDetail;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 路线明细Mapper接口
 * 提供路线明细表的数据库操作
 */
@Mapper
public interface VrpRouteDetailMapper extends BaseMapper<VrpRouteDetail> {

    /**
     * 根据任务ID查询所有路线明细
     * 
     * @param taskId 任务ID
     * @return 该任务下的所有路线明细
     */
    @Select("SELECT * FROM vrp_route_detail WHERE task_id = #{taskId} ORDER BY vehicle_id, sequence")
    List<VrpRouteDetail> selectByTaskId(@Param("taskId") Long taskId);

    /**
     * 根据任务ID删除所有路线明细
     * 用于重新计算前清除旧数据
     * 
     * @param taskId 任务ID
     * @return 删除的记录数
     */
    @Delete("DELETE FROM vrp_route_detail WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") Long taskId);
}
