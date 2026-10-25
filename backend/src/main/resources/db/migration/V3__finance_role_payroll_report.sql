-- ================================================
-- Argus V3: finance role, financial document isolation, payroll ledger, AI reports
-- ================================================

-- Excel documents uploaded/imported by finance are hidden from ordinary users.
ALTER TABLE excel_document ADD COLUMN IF NOT EXISTS finance_required BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE excel_document d
SET finance_required = TRUE
WHERE EXISTS (
    SELECT 1 FROM finance_import_batch b WHERE b.document_id = d.id
);

CREATE INDEX IF NOT EXISTS idx_excel_document_finance_status
    ON excel_document(finance_required, status);

-- Attendance ledger; staff may only view their own rows.
CREATE TABLE IF NOT EXISTS employee_attendance (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    work_date        DATE NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    check_in         TIME,
    check_out        TIME,
    late_minutes     INT NOT NULL DEFAULT 0,
    early_minutes    INT NOT NULL DEFAULT 0,
    overtime_minutes INT NOT NULL DEFAULT 0,
    leave_type       VARCHAR(32),
    leave_hours      DECIMAL(6,2) NOT NULL DEFAULT 0,
    remark           VARCHAR(500),
    created_by       BIGINT,
    created_by_name  VARCHAR(64),
    updated_by       BIGINT,
    updated_by_name  VARCHAR(64),
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_employee_attendance_user_date UNIQUE (user_id, work_date)
);

CREATE INDEX IF NOT EXISTS idx_attendance_user_month
    ON employee_attendance (user_id, work_date DESC);

-- Monthly payroll ledger; unique by user + month, staff only sees their own row.
CREATE TABLE IF NOT EXISTS employee_payroll (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL,
    payroll_month     VARCHAR(7) NOT NULL,
    work_days         INT NOT NULL DEFAULT 0,
    base_salary       DECIMAL(14,2) NOT NULL DEFAULT 0,
    performance_bonus DECIMAL(14,2) NOT NULL DEFAULT 0,
    attendance_bonus  DECIMAL(14,2) NOT NULL DEFAULT 0,
    overtime_pay      DECIMAL(14,2) NOT NULL DEFAULT 0,
    allowance         DECIMAL(14,2) NOT NULL DEFAULT 0,
    subsidy           DECIMAL(14,2) NOT NULL DEFAULT 0,
    other_bonus       DECIMAL(14,2) NOT NULL DEFAULT 0,
    social_security   DECIMAL(14,2) NOT NULL DEFAULT 0,
    housing_fund      DECIMAL(14,2) NOT NULL DEFAULT 0,
    personal_tax      DECIMAL(14,2) NOT NULL DEFAULT 0,
    deduction         DECIMAL(14,2) NOT NULL DEFAULT 0,
    other_deduction   DECIMAL(14,2) NOT NULL DEFAULT 0,
    net_salary        DECIMAL(14,2) NOT NULL DEFAULT 0,
    pay_status        VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    pay_date          DATE,
    remark            VARCHAR(1000),
    created_by        BIGINT,
    created_by_name   VARCHAR(64),
    updated_by        BIGINT,
    updated_by_name   VARCHAR(64),
    create_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_employee_payroll_user_month UNIQUE (user_id, payroll_month)
);

CREATE INDEX IF NOT EXISTS idx_employee_payroll_month
    ON employee_payroll (payroll_month DESC);

-- AI financial brief snapshots with full provenance.
CREATE TABLE IF NOT EXISTS finance_report (
    id                BIGSERIAL PRIMARY KEY,
    title             VARCHAR(255) NOT NULL,
    report_month      VARCHAR(7),
    content_markdown  TEXT NOT NULL,
    model             VARCHAR(128),
    generated_by      BIGINT,
    generated_by_name VARCHAR(64),
    status            VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    error_message     VARCHAR(1000),
    create_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_finance_report_time
    ON finance_report (create_time DESC);

-- PDF/export artifacts linked to generated reports.
CREATE TABLE IF NOT EXISTS finance_report_file (
    id               BIGSERIAL PRIMARY KEY,
    report_id        BIGINT,
    file_type        VARCHAR(20) NOT NULL DEFAULT 'PDF',
    object_key       VARCHAR(255) NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    file_size        BIGINT NOT NULL DEFAULT 0,
    file_path        VARCHAR(500),
    created_by       BIGINT,
    created_by_name  VARCHAR(64),
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_finance_report_file_report
    ON finance_report_file (report_id);
