package com.example.vrpdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.vrpdemo.entity.VrpTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务Mapper接口
 * 提供VRP计算任务表的数据库操作
 */
@Mapper
public interface VrpTaskMapper extends BaseMapper<VrpTask> {
    // BaseMapper已提供基础CRUD操作
    // insert, selectById, updateById, deleteById等方法
}
