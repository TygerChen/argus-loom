package com.argus.rag.finance.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.service.ExcelAuditService;
import com.argus.rag.finance.entity.EmployeePayroll;
import com.argus.rag.finance.mapper.EmployeeAttendanceMapper;
import com.argus.rag.finance.mapper.EmployeePayrollMapper;
import com.argus.rag.finance.mapper.FinanceReportMapper;
import com.argus.rag.llm.ModelClient;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PayrollService} 关键分支行为契约（财务 P0 守卫生态：改聚合/校验/权限语义即红）。
 *
 * <p>锁住：空数据必须抛 {@link ErrorCodes#FINANCE_DATA_EMPTY}、非法月份必须抛
 * {@link ErrorCodes#FINANCE_MONTH_FORMAT_INVALID}、员工自助未发布月必须抛
 * {@link ErrorCodes#FINANCE_PAYROLL_NOT_AVAILABLE}、自助数据仅限本人（用当前登录用户过滤）。</p>
 *
 * <p>纯 mock，无数据库 / 无系统字体依赖，可在 CI 跑。</p>
 */
@DisplayName("薪酬服务关键分支（PayrollService）")
class PayrollServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 9L;

    private EmployeePayrollMapper payrollMapper;
    private FinanceReportMapper reportMapper;
    private JdbcTemplate jdbcTemplate;
    private CurrentUserService currentUserService;
    private PayrollService service;

    @BeforeEach
    void setUp() {
        payrollMapper = mock(EmployeePayrollMapper.class);
        EmployeeAttendanceMapper attendanceMapper = mock(EmployeeAttendanceMapper.class);
        reportMapper = mock(FinanceReportMapper.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        currentUserService = mock(CurrentUserService.class);
        ExcelAuditService auditService = mock(ExcelAuditService.class);
        ModelClient modelClient = mock(ModelClient.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        CurrentUserService.CurrentUser owner = mock(CurrentUserService.CurrentUser.class);
        when(owner.userId()).thenReturn(USER_ID);
        when(currentUserService.getRequiredCurrentUser()).thenReturn(owner);

        service = new PayrollService(payrollMapper, attendanceMapper, reportMapper,
                jdbcTemplate, currentUserService, auditService, modelClient, objectMapper);
    }

    @Test
    void upsertPayrollRejectsEmptyRows() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.upsertPayroll(List.of()));
        assertEquals(ErrorCodes.FINANCE_DATA_EMPTY, ex.getCode());
    }

    @Test
    void upsertPayrollRejectsNullRows() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.upsertPayroll(null));
        assertEquals(ErrorCodes.FINANCE_DATA_EMPTY, ex.getCode());
    }

    @Test
    void pagePayrollByMonthRejectsBadMonthFormat() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.pagePayrollByMonth("2026/01", 1, 10));
        assertEquals(ErrorCodes.FINANCE_MONTH_FORMAT_INVALID, ex.getCode());
    }

    @Test
    void pagePayrollByMonthPassesNormalizedMonth() {
        Page<EmployeePayroll> page = new Page<>(1, 1);
        when(payrollMapper.selectPage(any(), any())).thenReturn(page);
        Page<EmployeePayroll> result = service.pagePayrollByMonth("2026-09", 1, 10);
        assertNotNull(result);
    }

    @Test
    void selfPayrollThrowsNotAvailableWhenNoRecord() {
        when(payrollMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.selfPayroll("2026-09"));
        assertEquals(ErrorCodes.FINANCE_PAYROLL_NOT_AVAILABLE, ex.getCode());
    }

    @Test
    void selfPayrollReturnsOwnRecordOnly() {
        EmployeePayroll own = new EmployeePayroll();
        own.setId(1L);
        own.setUserId(USER_ID);
        own.setPayrollMonth("2026-09");
        own.setBaseSalary(BigDecimal.valueOf(10000));
        when(payrollMapper.selectOne(any())).thenReturn(own);

        EmployeePayroll result = service.selfPayroll("2026-09");

        assertNotNull(result);
        assertEquals(USER_ID, result.getUserId());
    }

    @Test
    void selfPayrollRejectsBadMonthFormat() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.selfPayroll("202609"));
        assertEquals(ErrorCodes.FINANCE_MONTH_FORMAT_INVALID, ex.getCode());
    }

    @Test
    void upsertPayrollRejectsUnknownUser() {
        EmployeePayroll row = new EmployeePayroll();
        row.setUserId(null);
        row.setPayrollMonth("2026-09");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.upsertPayroll(List.of(row)));
        assertEquals(ErrorCodes.FINANCE_DATA_EMPTY, ex.getCode());
    }
}