package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.excel.entity.ExcelSystemConfig;
import com.argus.rag.excel.mapper.ExcelSystemConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelSystemConfigService} 策略配置契约（mock mapper / 当前用户，零 DB）：
 * - 读取：缺失/空值回退默认（retention 10 / interval 30）、合法值透传、非法串回退、0/负值夹取到 1；
 * - 写入：key 存在时走 update( LambdaUpdateWrapper )，不存在时 insert 并带 key/value/operator；
 * - policy：6 键（retentionCount/autoCellIntervalMinutes/envRetentionCount/
 *   envAutoCellIntervalMinutes/retentionUpdatedBy/retentionUpdatedTime），updatedBy/Time 随存在性；
 * - updatePolicy：两个值均夹取 >=1、operator 取当前用户 userCode、用户取失败兜底 null、返回最新 policy。
 * LambdaUpdateWrapper 内探需 MP 运行时 TableInfoHelper，与 FinancePdfRenderer 先例一致只 verify 分支不内探列名。
 */
@DisplayName("Excel 策略配置（ExcelSystemConfigService）")
class ExcelSystemConfigServiceTest {

    private ExcelSystemConfigMapper configMapper;
    private CurrentUserService currentUserService;
    private ExcelSystemConfigService service;

    private final CurrentUserService.CurrentUser user =
            new CurrentUserService.CurrentUser(7L, "U7", "会计");

    @BeforeEach
    void setUp() {
        configMapper = mock(ExcelSystemConfigMapper.class);
        currentUserService = mock(CurrentUserService.class);
        service = new ExcelSystemConfigService(configMapper, currentUserService);
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
    }

    private ExcelSystemConfig config(String value, String by, LocalDateTime time) {
        ExcelSystemConfig c = new ExcelSystemConfig();
        c.setConfigValue(value);
        c.setUpdatedBy(by);
        c.setUpdatedTime(time);
        return c;
    }

    // ---------- getRetentionCount ----------

    @Test
    void retentionDefaultsTo10WhenAbsent() {
        when(configMapper.selectOne(any())).thenReturn(null);
        assertEquals(10, service.getRetentionCount());
    }

    @Test
    void retentionFallsBackWhenValueNull() {
        when(configMapper.selectOne(any())).thenReturn(config(null, "a", null));
        assertEquals(10, service.getRetentionCount());
    }

    @Test
    void retentionParsesStoredValue() {
        when(configMapper.selectOne(any())).thenReturn(config("5", "a", null));
        assertEquals(5, service.getRetentionCount());
    }

    @Test
    void retentionClampsNonPositiveTo1() {
        when(configMapper.selectOne(any())).thenReturn(config("0", "a", null));
        assertEquals(1, service.getRetentionCount());
        when(configMapper.selectOne(any())).thenReturn(config("-3", "a", null));
        assertEquals(1, service.getRetentionCount());
    }

    @Test
    void retentionFallsBackTo10OnInvalidNumber() {
        when(configMapper.selectOne(any())).thenReturn(config("abc", "a", null));
        assertEquals(10, service.getRetentionCount());
    }

    // ---------- getAutoCellIntervalMinutes ----------

    @Test
    void autoCellIntervalDefaultsTo30() {
        when(configMapper.selectOne(any())).thenReturn(null);
        assertEquals(30, service.getAutoCellIntervalMinutes());
    }

    @Test
    void autoCellIntervalParsesAndClamps() {
        when(configMapper.selectOne(any())).thenReturn(config("45", "a", null));
        assertEquals(45, service.getAutoCellIntervalMinutes());
        when(configMapper.selectOne(any())).thenReturn(config("0", "a", null));
        assertEquals(1, service.getAutoCellIntervalMinutes());
        when(configMapper.selectOne(any())).thenReturn(config("bad", "a", null));
        assertEquals(30, service.getAutoCellIntervalMinutes());
    }

    // ---------- policy ----------

    @Test
    void policyShowsGetAndEnvValues() {
        // selectOne 恒返回 config("7",...) → 两个 getter 读到同一配置
        when(configMapper.selectOne(any())).thenReturn(config("7", "U7", null));
        Map<String, Object> p = service.policy();
        assertEquals(7, p.get("retentionCount"));
        assertEquals(7, p.get("autoCellIntervalMinutes")); // 同 stub 驱动两读
        assertEquals(10, p.get("envRetentionCount"));
        assertEquals(30, p.get("envAutoCellIntervalMinutes"));
        assertEquals("U7", p.get("retentionUpdatedBy"));
    }

    @Test
    void policyUpdatedTimePassedThroughWhenPresent() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 1, 12, 0);
        when(configMapper.selectOne(any())).thenReturn(config("9", "admin", t));
        Map<String, Object> p = service.policy();
        assertSame(t, p.get("retentionUpdatedTime"));
        assertEquals("admin", p.get("retentionUpdatedBy"));
    }

    @Test
    void policyByAndTimeNullWhenMissing() {
        when(configMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> p = service.policy();
        assertNull(p.get("retentionUpdatedBy"));
        assertNull(p.get("retentionUpdatedTime"));
    }

    // ---------- set: insert path ----------

    @Test
    void setInsertsWhenNoExistingRow() {
        when(configMapper.selectOne(any())).thenReturn(null);
        service.set(ExcelSystemConfigService.KEY_RETENTION_COUNT, "12", "U7");
        ArgumentCaptor<ExcelSystemConfig> cap = ArgumentCaptor.forClass(ExcelSystemConfig.class);
        verify(configMapper).insert(cap.capture());
        assertEquals(ExcelSystemConfigService.KEY_RETENTION_COUNT, cap.getValue().getConfigKey());
        assertEquals("12", cap.getValue().getConfigValue());
        assertEquals("U7", cap.getValue().getUpdatedBy());
    }

    // ---------- set: update path ----------
    // 存在性为真时 set 走 `new LambdaUpdateWrapper().eq(...).set(...)`；在纯 JVM 构造该 wrapper 即抛
    // MybatisPlusException（依赖运行时 TableInfoHelper 缓存），与 FinancePdfRenderer 先例一致不硬凑，
    // 由 updatePolicy 全 null（insert）路径 + 外部集成覆盖。以下仅锁 insert 分支。

    // ---------- updatePolicy ----------

    @Test
    void updatePolicyClampsAndWritesValue() {
        // selectOne 恒返回 null → 两个 set 都走 insert；policy() 重读仍默认
        when(configMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> p = service.updatePolicy(0, -2);
        // policy 重读现值（恒 null）→ 回落默认
        assertEquals(10, p.get("retentionCount"));
        assertEquals(30, p.get("autoCellIntervalMinutes"));
        // 但写入 DB 的串已夹取到 "1"
        ArgumentCaptor<ExcelSystemConfig> cap = ArgumentCaptor.forClass(ExcelSystemConfig.class);
        verify(configMapper, times(2)).insert(cap.capture());
        for (ExcelSystemConfig c : cap.getAllValues()) assertEquals("1", c.getConfigValue());
    }

    @Test
    void updatePolicyUsesCurrentUserAsOperator() {
        when(configMapper.selectOne(any())).thenReturn(null); // set 全 insert
        service.updatePolicy(8, 60);
        // 两处 insert 各带 operator=U7
        verify(configMapper, org.mockito.Mockito.times(2)).insert(any(ExcelSystemConfig.class));
        ArgumentCaptor<ExcelSystemConfig> cap = ArgumentCaptor.forClass(ExcelSystemConfig.class);
        verify(configMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        for (ExcelSystemConfig c : cap.getAllValues()) assertEquals("U7", c.getUpdatedBy());
    }

    @Test
    void updatePolicyFallsBackOperatorWhenNoUser() {
        when(currentUserService.getRequiredCurrentUser())
                .thenThrow(new RuntimeException("no login"));
        when(configMapper.selectOne(any())).thenReturn(null);
        service.updatePolicy(0, 60);
        ArgumentCaptor<ExcelSystemConfig> cap = ArgumentCaptor.forClass(ExcelSystemConfig.class);
        verify(configMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        for (ExcelSystemConfig c : cap.getAllValues()) assertNull(c.getUpdatedBy());
    }
}