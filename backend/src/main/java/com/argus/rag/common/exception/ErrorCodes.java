package com.argus.rag.common.exception;

/**
 * 稳定业务错误码总线。
 *
 * <p>集中登记需要前端/网关结构化识别（而非仅文本展示）的业务错误码，配合
 * {@link GlobalExceptionHandler} 透传：业务抛出带码的 {@link BusinessException} 后，
 * 统一响应 {@code ApiResponse.code} 会带上该码，前端可按码区分行为（如“模型不可用”触发
 * 重试/降级）。只放置被多处引用的错误码，避免为单一使用点提前建码（不过度设计）。</p>
 */
public final class ErrorCodes {

    /** 模型服务暂不可用（调用失败/超时/中断），前端可提示稍后重试。 */
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";

    /** 与 {@link #MODEL_UNAVAILABLE} 关联的用户可读文案，统一避免各模块字面量漂移。 */
    public static final String MODEL_UNAVAILABLE_MESSAGE = "模型服务暂时不可用，请稍后重试。";

    // ─────────────────────────── 财务域（finance）───────────────────────────

    /** 财务数据为空/缺失（薪酬、考勤等导出前提数据不完整），前端可提示补齐数据。 */
    public static final String FINANCE_DATA_EMPTY = "FINANCE_DATA_EMPTY";

    /** 目标月份薪酬尚未发布或不存在，前端可提示等待发布。 */
    public static final String FINANCE_PAYROLL_NOT_AVAILABLE = "FINANCE_PAYROLL_NOT_AVAILABLE";

    /** 财务简报记录（报表本身）不存在，可提示刷新列表。 */
    public static final String FINANCE_REPORT_NOT_FOUND = "FINANCE_REPORT_NOT_FOUND";

    /** HR 阅读/导出的 PDF 附件不存在，可提示重新生成。 */
    public static final String FINANCE_PDF_NOT_FOUND = "FINANCE_PDF_NOT_FOUND";

    /** PDF 生成链路失败（建目录/渲染/写盘/读取字节），可提示稍后重试或联系管理员。 */
    public static final String FINANCE_PDF_FILE_FAILED = "FINANCE_PDF_FILE_FAILED";

    /** AI 财务简报生成失败（模型侧异常），可提示稍后重试（可对接降级）。 */
    public static final String FINANCE_AI_REPORT_FAILED = "FINANCE_AI_REPORT_FAILED";

    /** 月份入参格式非法（应为 YYYY-MM）。 */
    public static final String FINANCE_MONTH_FORMAT_INVALID = "FINANCE_MONTH_FORMAT_INVALID";

    /** 财务期间已关闭，禁止该月的导入/编辑（任务 10 期间关闭）。 */
    public static final String FINANCE_PERIOD_CLOSED = "FINANCE_PERIOD_CLOSED";

    /** 关闭/重新打开期间必须填写原因（审计要求）。 */
    public static final String FINANCE_PERIOD_REASON_REQUIRED = "FINANCE_PERIOD_REASON_REQUIRED";

    /** 同一文件（SHA-256 相同）不允许重复导入，避免重复流水。 */
    public static final String FINANCE_FILE_DUPLICATED = "FINANCE_FILE_DUPLICATED";

    // ─────────────────────────── 账号/身份域（auth）───────────────────────────

    /** access/refresh token 非法或已失效，前端可据此跳登录或主动刷新。 */
    public static final String AUTH_TOKEN_INVALID = "AUTH_TOKEN_INVALID";

    /** refresh token 被重放使用（疑似复用/攻击），前端应强制重新登录。 */
    public static final String AUTH_TOKEN_REPLAYED = "AUTH_TOKEN_REPLAYED";

    /** 当前用户/用户不存在（会话主体缺失），可按会话失效处理。 */
    public static final String AUTH_USER_NOT_FOUND = "AUTH_USER_NOT_FOUND";

    /** 用户名或密码错误（登录凭证不匹配），前端展示统一文案，不泄露账号存在性。 */
    public static final String AUTH_CREDENTIALS_INVALID = "AUTH_CREDENTIALS_INVALID";

    /** 用户名/邮箱已被占用（注册去重），前端可提示换一个。 */
    public static final String AUTH_IDENTITY_EXISTS = "AUTH_IDENTITY_EXISTS";

    // ─────────────────────────── 账号管理域（user 管理操作）───────────────────────────

    /** 操作目标用户不存在（管理/查询/改密等资源操作），前端可提示刷新列表。 */
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    /** 修改密码时当前密码不正确。 */
    public static final String USER_PASSWORD_MISMATCH = "USER_PASSWORD_MISMATCH";

    /** 新密码与当前密码相同，需换一个。 */
    public static final String USER_PASSWORD_SAME_AS_OLD = "USER_PASSWORD_SAME_AS_OLD";

    /** 待设角色与当前角色一致，无需重复设置。 */
    public static final String USER_ROLE_ALREADY_SET = "USER_ROLE_ALREADY_SET";

    // ─────────────────────────── 组织/知识库成员域（group）───────────────────────────

    /** 操作目标不存在（成员/邀请/申请/被邀用户/组织），前端可提示刷新列表。 */
    public static final String GROUP_NOT_FOUND = "GROUP_NOT_FOUND";

    /** 目标申请/邀请已被处理过，前端应刷新列表而非重复操作。 */
    public static final String GROUP_ALREADY_HANDLED = "GROUP_ALREADY_HANDLED";

    /** 已存在待处理的申请/邀请，前端可提示先去处理或等待审批。 */
    public static final String GROUP_REQUEST_PENDING = "GROUP_REQUEST_PENDING";

    /** 操作无权/不合法（不能移除 OWNER、非本组成员、已入群、无权处理邀请等）。 */
    public static final String GROUP_ACTION_FORBIDDEN = "GROUP_ACTION_FORBIDDEN";

    /** 成员标识参数非法（groupId/被邀请用户非法）。 */
    public static final String GROUP_INVALID_TARGET = "GROUP_INVALID_TARGET";

    // ─────────────────────────── Excel 协作域（excel）───────────────────────────

    /** 目标资源不存在（文档/版本/导出任务），前端可提示刷新列表。 */
    public static final String EXCEL_NOT_FOUND = "EXCEL_NOT_FOUND";

    /** 文档处于回收站/已删除的流转冲突态（不能重复或要对非回收站文档做回收站操作）。 */
    public static final String EXCEL_TRASH_STATE = "EXCEL_TRASH_STATE";

    /** 分享链接无效/已过期/不存在/角色配置非法。 */
    public static final String EXCEL_SHARE_INVALID = "EXCEL_SHARE_INVALID";

    /** 财务文件不允许导出/生成分享链接等（财务数据隔离红线，前端须强阻断，不可降级）。 */
    public static final String EXCEL_FINANCE_FORBIDDEN = "EXCEL_FINANCE_FORBIDDEN";

    /** 导出任务状态机冲突（已完成无法取消/未完成无法下载/文件已清理）。 */
    public static final String EXCEL_EXPORT_STATE = "EXCEL_EXPORT_STATE";

    /** 通用权限不足（无权访问某分享/导出任务/协作对象）。 */
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";

    /** Sheet/文件解析失败（空 sheet / 无有效 sheet / 读取查询失败），前端可提示重传或换文件。 */
    public static final String EXCEL_PARSE_ERROR = "EXCEL_PARSE_ERROR";

    /** 资源已被其他操作并发修改（乐观锁冲突），前端应提示刷新后重试。 */
    public static final String CONCURRENT_MODIFIED = "CONCURRENT_MODIFIED";

    /** 上传文件超过大小/数量限制，前端应提示拆分或压缩后重试。 */
    public static final String FILE_LIMIT_EXCEEDED = "FILE_LIMIT_EXCEEDED";

    // ─────────────────────────── 知识库文档域（document / ingestion）───────────────────────────

    /** 目标文档不存在或已删除/未找到，前端可提示刷新列表。 */
    public static final String DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";

    /** 文档尚未就绪（处理中/存储信息缺失），前端可提示稍后再试预览/下载。 */
    public static final String DOCUMENT_NOT_READY = "DOCUMENT_NOT_READY";

    /** 文档处理/解析失败（解析/取存储/重处理/预览写入等），前端可提示重传。 */
    public static final String DOCUMENT_PROCESSING_FAILED = "DOCUMENT_PROCESSING_FAILED";

    /** 上传会话不存在/过期/非本人/已结束，前端应重新发起上传。 */
    public static final String UPLOAD_SESSION_INVALID = "UPLOAD_SESSION_INVALID";

    /** 上传分片不完整/校验失败/读取失败，前端可断点重传或重传该片。 */
    public static final String UPLOAD_INCOMPLETE = "UPLOAD_INCOMPLETE";

    // ─────────────────────────── 检索问答域（qa / retrieval）───────────────────────────

    /** 检索证据不足或不落地（分数低于阈值/无有效证据），拒绝编造答案。 */
    public static final String RETRIEVAL_NOT_GROUNDED = "RETRIEVAL_NOT_GROUNDED";

    // ─────────────────────────── HTTP Status 映射 ───────────────────────────

    /** 根据错误码返回对应的 HTTP 状态码；未登记的码一律 400。 */
    public static org.springframework.http.HttpStatus httpStatus(String code) {
        if (code == null) return org.springframework.http.HttpStatus.BAD_REQUEST;
        return switch (code) {
            // 401 Unauthorized
            case AUTH_TOKEN_INVALID, AUTH_TOKEN_REPLAYED, AUTH_CREDENTIALS_INVALID ->
                    org.springframework.http.HttpStatus.UNAUTHORIZED;
            // 403 Forbidden
            case GROUP_ACTION_FORBIDDEN, EXCEL_FINANCE_FORBIDDEN, PERMISSION_DENIED ->
                    org.springframework.http.HttpStatus.FORBIDDEN;
            case FILE_LIMIT_EXCEEDED ->
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;
            // 404 Not Found
            case EXCEL_NOT_FOUND, FINANCE_REPORT_NOT_FOUND, FINANCE_PDF_NOT_FOUND,
                 USER_NOT_FOUND, GROUP_NOT_FOUND, DOCUMENT_NOT_FOUND, AUTH_USER_NOT_FOUND ->
                    org.springframework.http.HttpStatus.NOT_FOUND;
            // 409 Conflict
            case EXCEL_TRASH_STATE, EXCEL_EXPORT_STATE, CONCURRENT_MODIFIED,
                 GROUP_ALREADY_HANDLED, GROUP_REQUEST_PENDING, AUTH_IDENTITY_EXISTS,
                 USER_ROLE_ALREADY_SET, DOCUMENT_NOT_READY ->
                    org.springframework.http.HttpStatus.CONFLICT;
            // 503 Service Unavailable
            case MODEL_UNAVAILABLE, FINANCE_AI_REPORT_FAILED ->
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
            // 500 Internal Server Error
            case FINANCE_PDF_FILE_FAILED, DOCUMENT_PROCESSING_FAILED ->
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
            // 其余（参数校验/解析/上传等）→ 400
            default -> org.springframework.http.HttpStatus.BAD_REQUEST;
        };
    }

    private ErrorCodes() {
    }
}
