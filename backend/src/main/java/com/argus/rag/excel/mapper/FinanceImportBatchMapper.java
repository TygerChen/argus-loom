package com.argus.rag.excel.mapper;

import com.argus.rag.excel.entity.FinanceImportBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 财务导入批次表的数据访问接口。
 */
@Mapper
public interface FinanceImportBatchMapper extends BaseMapper<FinanceImportBatch> {
}
