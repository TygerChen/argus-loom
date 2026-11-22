package com.argus.rag.assistant.trace.mapper;

import com.argus.rag.assistant.trace.entity.TaskTraceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务轨迹 Mapper（包路径以 .mapper 结尾——主类 @MapperScan 白名单自动扫描）。
 */
@Mapper
public interface TaskTraceMapper extends BaseMapper<TaskTraceEntity> {
}
