package com.argus.rag.common.task;

/**
 * 任务动作类型 —— 入口对请求动作面的分类（保守文本标记，非语义理解）。
 * <p>分类器误标只是审计噪音，漏标由第二步语义理解与工具层权限兜底；
 * 因此词表刻意宁滥勿缺。与 {@link TaskRiskLevel} 联动：
 * DELETE/EXPORT/EXTERNAL_SEND/BATCH_MODIFY 一律 HIGH + need_confirmation；
 * UPLOAD 为写知识库的中风险（自动发布是现行产品行为，审核门属可选演进）。</p>
 */
public enum TaskActionType {

    /** 只读查询/问答/下载/预览 */
    READ,

    /** 删除（文档/群组/数据行） */
    DELETE,

    /** 数据导出/批量下载 */
    EXPORT,

    /** 对外发送（钉钉推送/分享链接/邮件外发） */
    EXTERNAL_SEND,

    /** 批量修改/批量导入 */
    BATCH_MODIFY,

    /** 写入知识库（文档上传——写操作入口，区别于只读问答） */
    UPLOAD
}
