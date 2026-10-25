package com.argus.rag.excel.mapper;

import com.argus.rag.excel.entity.ExcelDocumentPermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * ExcelDocumentPermission 实体的数据访问接口（MyBatis-Plus Mapper）。
 * <p>以「ExcelDocumentPermission」为持久化模型，向上层 Service 提供对这张表的增删改查、条件/分页查询等能力，CRUD 无需自行写 SQL。</p>
 */
@Mapper
public interface ExcelDocumentPermissionMapper extends BaseMapper<ExcelDocumentPermission> {
}
