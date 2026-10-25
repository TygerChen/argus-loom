package com.argus.rag.metrics.service;

import com.argus.rag.metrics.mapper.LlmUsageRecordMapper;
import com.argus.rag.metrics.model.enums.StatsPeriod;
import com.argus.rag.metrics.model.vo.MetricsOverviewVO;
import com.argus.rag.metrics.model.vo.UsageRankItemVO;
import com.argus.rag.metrics.model.vo.UsageStatsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LlmUsageStatisticsService} 委托契约（mock mapper，零 DB / 零网络）：
 * - 级别委派不串位：getUserStats→(userId, null) vs getGroupStats→(null, groupId) vs getPlatformStats→(null, null)，
 *   均透传 @param startTime；
 * - getOverview：今日三字段映射 + 30 天趋势接 LAST_30_DAYS 起点、module=null；
 * - getDailyTrend / getUserRank / getGroupRank：透传 period 起点 + limit。
 * 时点用捕获的 mapper 实参断言 period 绑定（避免再算 now 造成边界漂移）。
 */
@DisplayName("LLM 用量统计（LlmUsageStatisticsService）")
class LlmUsageStatisticsServiceTest {

    private final LlmUsageRecordMapper mapper = mock(LlmUsageRecordMapper.class);
    private final LlmUsageStatisticsService svc = new LlmUsageStatisticsService(mapper);

    private static LocalDateTime startOf(StatsPeriod p) { return p.getStartTime(); }

    // ---------- 用户级 ----------

    @Test
    void getUserStatsPassesUserIdAndGroupNull() {
        UsageStatsVO s = new UsageStatsVO();
        when(mapper.selectUsageStats(any(), any(), any())).thenReturn(s);
        assertSame(s, svc.getUserStats(7L, StatsPeriod.LAST_7_DAYS));
        ArgumentCaptor<Long> uCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> gCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<LocalDateTime> tCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).selectUsageStats(uCap.capture(), gCap.capture(), tCap.capture());
        assertEquals(7L, uCap.getValue());
        assertNull(gCap.getValue());
        assertEquals(startOf(StatsPeriod.LAST_7_DAYS), tCap.getValue());
    }

    // ---------- 群组级 ----------

    @Test
    void getGroupStatsPassesGroupAndNullUser() {
        when(mapper.selectUsageStats(any(), any(), any())).thenReturn(new UsageStatsVO());
        svc.getGroupStats(9L, StatsPeriod.LAST_14_DAYS);
        ArgumentCaptor<Long> uCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> gCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<LocalDateTime> tCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).selectUsageStats(uCap.capture(), gCap.capture(), tCap.capture());
        assertNull(uCap.getValue());
        assertEquals(9L, gCap.getValue());
        assertEquals(startOf(StatsPeriod.LAST_14_DAYS), tCap.getValue());
    }

    // ---------- 平台级 ----------

    @Test
    void getPlatformStatsNullsBothScopes() {
        when(mapper.selectUsageStats(any(), any(), any())).thenReturn(new UsageStatsVO());
        svc.getPlatformStats(StatsPeriod.TODAY);
        ArgumentCaptor<Long> uCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> gCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<LocalDateTime> tCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).selectUsageStats(uCap.capture(), gCap.capture(), tCap.capture());
        assertNull(uCap.getValue());
        assertNull(gCap.getValue());
        assertEquals(startOf(StatsPeriod.TODAY), tCap.getValue());
    }

    // ---------- 概览 ----------

    @Test
    void getOverviewMapsTodayStatsAndTrend() {
        UsageStatsVO s = new UsageStatsVO();
        s.setTotalRequests(10L);
        s.setTotalTokens(2000L);
        s.setTotalCost(new BigDecimal("1.25"));
        s.setSuccessRate(new BigDecimal("98.5"));
        when(mapper.selectUsageStats(isNull(), isNull(), any())).thenReturn(s);

        MetricsOverviewVO.DailyStats ds = new MetricsOverviewVO.DailyStats();
        ds.setDate("2026-09-18"); ds.setRequests(5L); ds.setTokens(1000L); ds.setCost(new BigDecimal("0.5"));
        List<MetricsOverviewVO.DailyStats> trend = new ArrayList<>(List.of(ds));
        when(mapper.selectDailyTrend(any(LocalDateTime.class), isNull())).thenReturn(trend);

        MetricsOverviewVO o = svc.getOverview();

        assertEquals(10L, o.getTodayRequests());
        assertEquals(2000L, o.getTodayTokens());
        assertEquals(new BigDecimal("1.25"), o.getTodayCost());
        assertEquals(new BigDecimal("98.5"), o.getTodaySuccessRate());
        assertSame(trend, o.getDailyTrend());
    }

    @Test
    void getOverviewWiresTodayForStatsAndLast30ForTrend() {
        when(mapper.selectUsageStats(isNull(), isNull(), any())).thenReturn(new UsageStatsVO());
        when(mapper.selectDailyTrend(any(LocalDateTime.class), isNull()))
                .thenReturn(new ArrayList<>());

        svc.getOverview();

        // 今日统计：TODAY 起点
        ArgumentCaptor<Long> uCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> gCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<LocalDateTime> statsTime = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).selectUsageStats(uCap.capture(), gCap.capture(), statsTime.capture());
        assertNull(uCap.getValue()); assertNull(gCap.getValue());
        assertEquals(startOf(StatsPeriod.TODAY), statsTime.getValue());

        // 趋势：LAST_30_DAYS 起点、module=null
        ArgumentCaptor<LocalDateTime> trendTime = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<String> module = ArgumentCaptor.forClass(String.class);
        verify(mapper).selectDailyTrend(trendTime.capture(), module.capture());
        assertEquals(startOf(StatsPeriod.LAST_30_DAYS), trendTime.getValue());
        assertNull(module.getValue());
    }

    // ---------- 趋势 / 排行 ----------

    @Test
    void getDailyTrendForwardsPeriodAndModule() {
        when(mapper.selectDailyTrend(any(LocalDateTime.class), any())).thenReturn(new ArrayList<>());
        svc.getDailyTrend(StatsPeriod.LAST_14_DAYS, "chat");
        ArgumentCaptor<LocalDateTime> tCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<String> mCap = ArgumentCaptor.forClass(String.class);
        verify(mapper).selectDailyTrend(tCap.capture(), mCap.capture());
        assertEquals(startOf(StatsPeriod.LAST_14_DAYS), tCap.getValue());
        assertEquals("chat", mCap.getValue());
    }

    @Test
    void getUserRankForwardsPeriodAndLimit() {
        when(mapper.selectUserRank(any(LocalDateTime.class), anyInt())).thenReturn(new ArrayList<>());
        svc.getUserRank(StatsPeriod.LAST_30_DAYS, 5);
        ArgumentCaptor<LocalDateTime> tCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Integer> lCap = ArgumentCaptor.forClass(Integer.class);
        verify(mapper).selectUserRank(tCap.capture(), lCap.capture());
        assertEquals(startOf(StatsPeriod.LAST_30_DAYS), tCap.getValue());
        assertEquals(5, lCap.getValue());
    }

    @Test
    void getGroupRankForwardsPeriodAndLimit() {
        when(mapper.selectGroupRank(any(LocalDateTime.class), anyInt())).thenReturn(new ArrayList<>());
        svc.getGroupRank(StatsPeriod.TODAY, 3);
        ArgumentCaptor<LocalDateTime> tCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Integer> lCap = ArgumentCaptor.forClass(Integer.class);
        verify(mapper).selectGroupRank(tCap.capture(), lCap.capture());
        assertEquals(startOf(StatsPeriod.TODAY), tCap.getValue());
        assertEquals(3, lCap.getValue());
    }
}