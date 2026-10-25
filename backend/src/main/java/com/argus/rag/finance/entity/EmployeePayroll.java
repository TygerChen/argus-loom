package com.argus.rag.finance.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 月度薪酬台账：员工只能查看本人，财务/领导统一维护。
 */
@TableName("employee_payroll")
@Data
public class EmployeePayroll {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 结算月份，格式 YYYY-MM */
    private String payrollMonth;

    private Integer workDays;

    private BigDecimal baseSalary;

    private BigDecimal performanceBonus;

    private BigDecimal attendanceBonus;

    private BigDecimal overtimePay;

    private BigDecimal allowance;

    private BigDecimal subsidy;

    private BigDecimal otherBonus;

    private BigDecimal socialSecurity;

    private BigDecimal housingFund;

    private BigDecimal personalTax;

    private BigDecimal deduction;

    private BigDecimal otherDeduction;

    private BigDecimal netSalary;

    /** DRAFT 草稿 / PAID 已发放 / VOID 作废 */
    private String payStatus;

    private LocalDate payDate;

    private String remark;

    private Long createdBy;

    private String createdByName;

    private Long updatedBy;

    private String updatedByName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
