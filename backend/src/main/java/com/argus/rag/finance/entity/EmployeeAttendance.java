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
import java.time.LocalTime;

/**
 * 每日考勤台账：员工仅可查看本人，财务/领导统一维护。
 */
@TableName("employee_attendance")
@Data
public class EmployeeAttendance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate workDate;

    /** 考勤状态：NORMAL 正常 / LATE 迟到 / EARLY 早退 / LEAVE 请假 / ABSENT 缺勤 / OVERTIME 加班 */
    private String status;

    private LocalTime checkIn;

    private LocalTime checkOut;

    private Integer lateMinutes;

    private Integer earlyMinutes;

    private Integer overtimeMinutes;

    private String leaveType;

    private BigDecimal leaveHours;

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
