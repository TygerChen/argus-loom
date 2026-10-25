package com.argus.rag.excel.mapper;

import com.argus.rag.excel.entity.FinanceLedgerRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 财务标准流水表的数据访问接口。
 */
@Mapper
public interface FinanceLedgerRecordMapper extends BaseMapper<FinanceLedgerRecord> {

    /**
     * 行级悲观锁读取：把 fingerprint 乐观锁的“读-比-写”变得原子。
     * 配合事务 REPEATABLE_READ，第二个事务在此会阻塞至首个提交并读到最新版本，
     * 进而在 {@code batchUpdateRecords} 的 fingerprint 比对中判为冲突，避免并发覆盖。
     */
    @Select("SELECT * FROM finance_ledger_record WHERE id = #{id} FOR UPDATE")
    FinanceLedgerRecord selectByIdForUpdate(@Param("id") Long id);
}
