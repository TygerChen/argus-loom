package com.argus.rag.common.task.mapper;

import com.argus.rag.common.task.entity.EntryLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 入口审计流水 Mapper。
 * <p>包路径刻意以 {@code .mapper} 结尾——主类 {@code @MapperScan} 的
 * {@code com.argus.rag.**.mapper} 白名单会自动扫描到，无需手工注册
 * （BYOK settings 包当年就踩过"新包 Mapper bean not found"的坑）。</p>
 */
@Mapper
public interface EntryLogMapper extends BaseMapper<EntryLogEntity> {
}
