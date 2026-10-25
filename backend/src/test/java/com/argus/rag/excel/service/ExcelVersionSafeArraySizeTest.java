package com.argus.rag.excel.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ExcelVersionService#safeArraySize(String)} 行为锁
 * （纯 JVM、static 同包直调，零 mapper/IO）。
 * <p>锁契约：对 celldata JSON 提炼版本快照 cell 计数时防御性取值——合法数组→元素数、
 * 空数组→0、null→0、非法 JSON（含异常）→一律 0 回退，绝不让单格计数失败阻断快照主流程。</p>
 */
@DisplayName("单元格 JSON 数组尺寸防御取值（ExcelVersionService.safeArraySize）")
class ExcelVersionSafeArraySizeTest {

    @Test
    void legalArrayReturnsSize() {
        assertEquals(3, ExcelVersionService.safeArraySize("[{\"r\":0},{\"r\":1},{\"r\":2}]"));
    }

    @Test
    void emptyArrayReturnsZero() {
        assertEquals(0, ExcelVersionService.safeArraySize("[]"));
    }

    @Test
    void nullYieldsZero() {
        assertEquals(0, ExcelVersionService.safeArraySize(null));
    }

    @Test
    void blankYieldsZero() {
        assertEquals(0, ExcelVersionService.safeArraySize("   "));
    }

    @Test
    void invalidJsonYieldsZero() {
        assertEquals(0, ExcelVersionService.safeArraySize("not an array"));
        assertEquals(0, ExcelVersionService.safeArraySize("{\"a\":1}"));
    }
}