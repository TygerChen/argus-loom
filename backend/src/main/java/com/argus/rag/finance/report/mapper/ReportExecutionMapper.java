package com.argus.rag.finance.report.mapper;

import com.argus.rag.finance.report.entity.ReportExecution;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 报表执行记录表的数据访问接口。 */
@Mapper
public interface ReportExecutionMapper extends BaseMapper<ReportExecution> {
}
