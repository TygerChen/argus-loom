package com.argus.rag.excel.mapper;

import com.argus.rag.excel.entity.ExcelSheetChunkDelta;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Excel 单元格增量 Mapper（MyBatis-Plus）。
 */
@Mapper
public interface ExcelSheetChunkDeltaMapper extends BaseMapper<ExcelSheetChunkDelta> {
}
