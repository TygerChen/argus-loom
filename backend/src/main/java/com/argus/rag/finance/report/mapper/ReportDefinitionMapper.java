package com.argus.rag.finance.report.mapper;

import com.argus.rag.finance.report.entity.ReportDefinition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 报表定义表的数据访问接口。 */
@Mapper
public interface ReportDefinitionMapper extends BaseMapper<ReportDefinition> {
}
