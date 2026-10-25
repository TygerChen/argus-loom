package com.argus.rag.excel.mapper;

import com.argus.rag.excel.entity.FinancePeriod;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 财务期间表访问层。 */
@Mapper
public interface FinancePeriodMapper extends BaseMapper<FinancePeriod> {
}
