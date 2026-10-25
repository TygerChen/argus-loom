package com.argus.rag.assistant.model.dto.chat;

import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record AssistantChatRequest(
        /**
         * 会话ID，标识本次聊天所属的会话
         * <p>必填，必须为正整数</p>
         */
        @NotNull(message = "sessionId 不能为空")
        @Positive(message = "sessionId 非法")
        Long sessionId,
        /**
         * 用户输入的聊天消息内容
         * <p>必填，不能为空字符串，最大长度4000个字符</p>
         */
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "message 长度不能超过 4000")
        String message,
        /**
         * 工具模式，指定当前对话的工作模式
         * <p>必填，可选值：{@link AssistantToolMode#CHAT 纯对话模式}、{@link AssistantToolMode#KB_SEARCH 知识库检索模式}</p>
         */
        @NotNull(message = "toolMode 不能为空")
        AssistantToolMode toolMode,
        /**
         * 知识库组ID，仅当 toolMode 为 {@link AssistantToolMode#KB_SEARCH} 时必须填写
         * <p>选填，如果填写则必须为正整数；CHAT 模式下不应传递此字段</p>
         */
        @Positive(message = "groupId 非法")
        Long groupId,
        /**
         * 知识库组ID列表，便于一次检索多个知识库。
         * <p>选填；与 {@link #groupId} 同时传入时取两者的并集。</p>
         */
        List<Long> groupIds,
        /**
         * 知识库路由策略，仅当 toolMode 为 {@link AssistantToolMode#KB_SEARCH} 时生效。
         * <p>缺省时：显式传了 groupId/groupIds 按 {@link KbRouteStrategy#SELECTED}，
         * 否则按 {@link KbRouteStrategy#AUTO}。</p>
         */
        KbRouteStrategy routeStrategy
) {

    /**
     * 解析实际生效的知识库组ID列表（去重、排序、兼容旧字段）。
     */
    public List<Long> effectiveGroupIds() {
        Set<Long> ids = new LinkedHashSet<>();
        if (groupId != null && groupId > 0) {
            ids.add(groupId);
        }
        if (groupIds != null) {
            for (Long id : groupIds) {
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 决定本次问答实际采用的知识库路由策略。
     * <p>优先级：用户显式指定的 {@code routeStrategy} 最高；未指定时，若当前没有选中任何知识库组
     * （{@code effectiveGroupIds} 为空）则回退到 {@link KbRouteStrategy#AUTO}（让系统自动决定搜哪个库），
     * 否则视为 {@link KbRouteStrategy#SELECTED}（按选中的组检索）。</p>
     */
    public KbRouteStrategy effectiveRouteStrategy() {
        if (routeStrategy != null) {
            return routeStrategy;
        }
        return effectiveGroupIds().isEmpty() ? KbRouteStrategy.AUTO : KbRouteStrategy.SELECTED;
    }
}
