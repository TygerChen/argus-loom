package com.argus.rag.finance.service;

import com.argus.rag.finance.entity.EmployeeAttendance;
import com.argus.rag.finance.entity.EmployeePayroll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PayrollService#computeNetSalary(EmployeePayroll)} 与 {@link PayrollService#normalizeAmounts(EmployeePayroll)}
 * 薪酬演算行为锁（纯 JVM、静态同包直调、零构造服务、零 IO/DB/LLM）。
 * <p>锁契约：实发工资 = 各收入项和 − 各扣除项和；空额归一为 0、缺省 workDays=0、缺省状态=DRAFT。</p>
 */
@DisplayName("薪酬演算（PayrollService.computeNetSalary/normalizeAmounts）")
class PayrollSalaryTest {

    private static EmployeePayroll row() {
        return new EmployeePayroll();
    }

    private static EmployeePayroll fullRow(BigDecimal base, BigDecimal perf, BigDecimal attend, BigDecimal overtime,
                                           BigDecimal allow, BigDecimal subsidy, BigDecimal otherBonus,
                                           BigDecimal social, BigDecimal fund) {
        EmployeePayroll r = row();
        r.setBaseSalary(base);
        r.setPerformanceBonus(perf);
        r.setAttendanceBonus(attend);
        r.setOvertimePay(overtime);
        r.setAllowance(allow);
        r.setSubsidy(subsidy);
        r.setOtherBonus(otherBonus);
        r.setSocialSecurity(social);
        r.setHousingFund(fund);
        return r;
    }

    // ---------- computeNetSalary ----------

    @Test
    void netEqualsAllIncomeMinusAllDeductions() {
        EmployeePayroll r = fullRow(bd("10000"), bd("2000"), bd("300"), bd("500"),
                bd("400"), bd("100"), bd("50"),
                bd("800"), bd("1000"));
        r.setPersonalTax(bd("300"));
        r.setDeduction(bd("200"));
        r.setOtherDeduction(bd("50"));
        // income = 10000+2000+300+500+400+100+50 = 13350
        // deduction = 800+1000+300+200+50 = 2350 → net 11000
        assertEquals(bd("11000"), PayrollService.computeNetSalary(r));
    }

    @Test
    void computeWithOnlyBaseAndSocialSecurity() {
        EmployeePayroll r = fullRow(bd("8000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                bd("700"), bd("500"));
        r.setPersonalTax(BigDecimal.ZERO);
        r.setDeduction(BigDecimal.ZERO);
        r.setOtherDeduction(BigDecimal.ZERO);
        assertEquals(bd("6800"), PayrollService.computeNetSalary(r));
    }

    @Test
    void computeAllZeroYieldsZero() {
        EmployeePayroll r = fullRow(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        r.setPersonalTax(BigDecimal.ZERO);
        r.setDeduction(BigDecimal.ZERO);
        r.setOtherDeduction(BigDecimal.ZERO);
        assertEquals(BigDecimal.ZERO, PayrollService.computeNetSalary(r));
    }

    // ---------- normalizeAmounts ----------

    @Test
    void normalizeDefaultsNullAmountsWorkDaysAndStatus() {
        EmployeePayroll r = row();
        PayrollService.normalizeAmounts(r);
        assertEquals(BigDecimal.ZERO, r.getBaseSalary());
        assertEquals(BigDecimal.ZERO, r.getPerformanceBonus());
        assertEquals(Integer.valueOf(0), r.getWorkDays());
        assertEquals("DRAFT", r.getPayStatus());
    }

    @Test
    void normalizePreservesExistingValuesAndStatus() {
        EmployeePayroll r = fullRow(bd("9000"), bd("500"), bd("0"), bd("0"),
                bd("0"), bd("0"), bd("0"),
                bd("600"), bd("0"));
        r.setWorkDays(22);
        r.setPayStatus("PAID");
        PayrollService.normalizeAmounts(r);
        assertEquals(bd("9000"), r.getBaseSalary());
        assertEquals(Integer.valueOf(22), r.getWorkDays());
        assertEquals("PAID", r.getPayStatus());
    }

    @Test
    void normalizeKeepsBlankStatusAndPreservesAmounts() {
        EmployeePayroll r = fullRow(bd("12000"), bd("1000"), bd("100"), bd("0"),
                bd("0"), bd("0"), bd("0"),
                bd("900"), bd("900"));
        r.setPayStatus("   ");
        PayrollService.normalizeAmounts(r);
        assertEquals("DRAFT", r.getPayStatus());
        assertEquals(bd("1000"), r.getPerformanceBonus());
    }

    // ---------- normalizeAttendanceRow ----------

    @Test
    void attendanceNormalizesNullsAndBlankStatus() {
        EmployeeAttendance a = new EmployeeAttendance();
        PayrollService.normalizeAttendanceRow(a);
        assertEquals(0, a.getLateMinutes());
        assertEquals(0, a.getEarlyMinutes());
        assertEquals(0, a.getOvertimeMinutes());
        assertEquals(BigDecimal.ZERO, a.getLeaveHours());
        assertEquals("NORMAL", a.getStatus());
    }

    @Test
    void attendanceUppercasesTrimmedStatusAndPreservesMinutes() {
        EmployeeAttendance a = new EmployeeAttendance();
        a.setLateMinutes(15);
        a.setLeaveHours(bd("2"));
        a.setStatus(" overtime ");
        PayrollService.normalizeAttendanceRow(a);
        assertEquals(15, a.getLateMinutes());
        assertEquals("OVERTIME", a.getStatus());
        assertEquals(bd("2"), a.getLeaveHours());
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}