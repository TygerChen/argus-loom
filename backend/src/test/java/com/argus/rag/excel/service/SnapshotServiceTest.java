package com.argus.rag.excel.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SnapshotService}{@code scheduledSnapshot} 定时快照契约（mock {@link ExcelVersionService}）：
 * - 已触达文档（上次编辑距今 <1h）必然触发一次 {@code TYPE_CELL "定时自动快照"}；
 * - 无编辑记录 → 空跑不产生任何快照；
 * - 超过 24h 的旧档 → 跳过（分钟窗口谓词不救回，>=24h 恒不生成）；
 * - 单文档快照失败被吞、不阻断其余文档、总数不计失败项；
 * - 兼容接口 {@link #createSnapshot(Long)} 委托一次手动单元快照。
 * 注：1h~24h 且落在分钟 <code>now.getMinute()%30&lt;5</code> 之内的窗口态依赖真实系统分钟，
 * 非确定性无法单测，不做硬凑（同 FinancePdfRenderer 先例）。旧档通过反射 seed 私有
 * {@code lastEditTimes} 注入过去时间以稳定锁行为。
 */
@DisplayName("Excel 定时快照（SnapshotService）")
class SnapshotServiceTest {

    private ExcelVersionService versionService;
    private SnapshotService service;

    @BeforeEach
    void setUp() {
        versionService = mock(ExcelVersionService.class);
        service = new SnapshotService(versionService);
    }

    /** 向私有(final)缓冲写入指定文档的"上次编辑时间"，规避真实 clock；只改 map 内容不改引用。 */
    @SuppressWarnings("unchecked")
    private void seedEditTime(Long docId, LocalDateTime since) throws Exception {
        Field f = SnapshotService.class.getDeclaredField("lastEditTimes");
        f.setAccessible(true);
        ConcurrentHashMap<Long, LocalDateTime> map =
                (ConcurrentHashMap<Long, LocalDateTime>) f.get(service);
        map.put(docId, since);
    }

    @Test
    void touchRecordsEditAndSnapshotFiresWithinHour() {
        service.touch(1L); // 现在时刻 → since < 1h 恒成立
        service.scheduledSnapshot();
        verify(versionService).createSnapshot(eq(1L),
                eq(ExcelVersionService.TYPE_CELL), eq("定时自动快照"));
    }

    @Test
    void touchMarksDocumentEdited() throws Exception {
        service.touch(1L);
        Field f = SnapshotService.class.getDeclaredField("lastEditTimes");
        f.setAccessible(true);
        ConcurrentHashMap<Long, LocalDateTime> map =
                (ConcurrentHashMap<Long, LocalDateTime>) f.get(service);
        org.junit.jupiter.api.Assertions.assertTrue(map.containsKey(1L));
    }

    @Test
    void scheduledNoopWhenNoEdits() {
        service.scheduledSnapshot();
        verify(versionService, never()).createSnapshot(any(), any(), any());
    }

    @Test
    void scheduledSkipsStaleDocumentOlderThan24h() throws Exception {
        seedEditTime(1L, LocalDateTime.now().minusHours(26));
        service.scheduledSnapshot();
        verify(versionService, never()).createSnapshot(any(), any(), any());
    }

    @Test
    void scheduledSkipsExactly24hBoundary() throws Exception {
        // since.toHours()==24 → 第二谓词 (since<24) 假 → 不生成
        seedEditTime(1L, LocalDateTime.now().minusHours(24).minusMinutes(1));
        service.scheduledSnapshot();
        verify(versionService, never()).createSnapshot(any(), any(), any());
    }

    @Test
    void scheduledSwallowsPerDocumentFailure() {
        doThrow(new RuntimeException("boom"))
                .when(versionService).createSnapshot(eq(1L), eq(ExcelVersionService.TYPE_CELL), any());
        service.touch(1L);
        // 失败被吞掉，scheduled 不抛、其它文档继续
        assertDoesNotThrow(() -> service.scheduledSnapshot());
    }

    @Test
    void legacyCreateSnapshotDelegatesManualCellSnapshot() {
        service.createSnapshot(7L);
        verify(versionService).createSnapshot(eq(7L),
                eq(ExcelVersionService.TYPE_CELL), any());
    }
}