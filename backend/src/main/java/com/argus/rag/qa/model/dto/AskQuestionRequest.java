package com.argus.rag.qa.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 知识问答请求 DTO。
 * <p>
 * 客户端提交问题时的入参：目标群组 ID、用户问题文本、多轮会话 ID（可选）。
 * 系统会在指定群组的知识库范围内检索并回答问题。
 * </p>
 * <p>
 * <b>多轮会话（V12）</b>：携带 {@code conversationId} 且未超过延续时间窗时，
 * 自动延续该会话——服务端用滚动摘要与近期原文补全追问的指代与省略，
 * 再进入检索与生成；不携带或超窗则新建会话。响应会回传会话 ID 供后续轮次携带。
 * </p>
 */
public class AskQuestionRequest {

    /** 目标群组 ID，必填，必须为正整数，用于限定知识库检索范围 */
    @NotNull(message = "groupId 不能为空")
    @Positive(message = "groupId 非法")
    private Long groupId;

    /**
     * 用户问题文本，必填，不能为空白。
     * 上限 8000 字符：兼容"粘贴材料 + 尾部提问"的长输入场景；
     * 超过 8000 的整份文档应走文档上传链路（分片上传 → ETL → 检索引用）。
     */
    @NotBlank(message = "问题不能为空")
    @Size(max = 8000, message = "问题长度不能超过 8000，超长内容请使用文档上传")
    private String question;

    /**
     * 多轮会话 ID，可选。携带时服务端校验归属（本人 + 同群组 + 延迟时间窗内），
     * 通过则延续会话（启用检索改写与滚动摘要记忆）；不携带、超窗或不匹配则新建会话。
     */
    @Positive(message = "conversationId 非法")
    private Long conversationId;

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }
}
