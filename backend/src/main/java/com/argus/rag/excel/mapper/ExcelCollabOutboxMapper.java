package com.argus.rag.excel.mapper;

import com.argus.rag.excel.entity.ExcelCollabOutbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 协同事件 Outbox 表访问层（任务 9）。
 */
public interface ExcelCollabOutboxMapper extends BaseMapper<ExcelCollabOutbox> {

    /** 按写入顺序取一批待发送事件（PENDING），供发布器广播。 */
    List<ExcelCollabOutbox> selectPending(@Param("limit") int limit);
}
