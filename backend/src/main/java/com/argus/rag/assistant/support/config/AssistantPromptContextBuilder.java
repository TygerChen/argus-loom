package com.argus.rag.assistant.support.config;

import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import com.argus.rag.prompt.PromptTemplate;
import com.argus.rag.prompt.PromptTemplateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 助手 Prompt 上下文构建器（重构版）。
 *
 * <p>文章依据：</p>
 * <ul>
 *   <li>第二部分第2节 — 用 XML 标签分隔指令与用户数据（&lt;user_input&gt; / &lt;knowledge&gt;）</li>
 *   <li>第六部分第4节 — 分层 Prompt 架构：基础规则 + 业务角色 + 任务层</li>
 *   <li>第四部分第2节 — 模板适合重复任务，变量由业务数据动态填充</li>
 *   <li>第五部分第5节 — 系统规则与用户数据使用清晰边界分隔，防 Prompt Injection</li>
 * </ul>
 *
 * <p>分层结构：</p>
 * <pre>
 *   [assistant-base.yml] 基础规则层（复用）
 *       + [assistant-kb-search.yml] KB 模式任务层（按 toolMode 叠加）
 *       + 上下文变量（sessionId、toolMode、groupId）
 *       + &lt;user_input&gt; 标签包裹用户消息
 *       + &lt;knowledge&gt; 标签包裹检索资料
 * </pre>
 */
@Component
public class AssistantPromptContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(AssistantPromptContextBuilder.class);

    private static final String BASE_TEMPLATE = "assistant-base";
    private static final String KB_SEARCH_TEMPLATE = "assistant-kb-search";

    private final PromptTemplateManager templateManager;

    public AssistantPromptContextBuilder(PromptTemplateManager templateManager) {
        this.templateManager = templateManager;
    }

    /**
     * 构建 Agent 的对话系统指令（system instruction）。
     */
    public String buildChatInstruction(
            Long userId,
            Long sessionId,
            AssistantToolMode toolMode,
            List<Long> groupIds,
            KbRouteStrategy routeStrategy
    ) {
        // 1. 基础规则层（从模板加载）
        String basePrompt = templateManager.render(BASE_TEMPLATE, Map.of());
        if (basePrompt.isEmpty()) {
            log.error("Assistant base prompt template '{}' not found, falling back to inline", BASE_TEMPLATE);
            basePrompt = fallbackBasePrompt();
        }

        StringBuilder builder = new StringBuilder(basePrompt);
        builder.append(System.lineSeparator()).append(System.lineSeparator());

        // 2. 上下文变量注入
        builder.append("## 当前上下文").append(System.lineSeparator());
        builder.append("会话ID：").append(sessionId).append(System.lineSeparator());
        builder.append("当前模式：").append(toolMode.name()).append(System.lineSeparator());
        builder.append("当前日期：").append(LocalDate.now()).append(System.lineSeparator());
        if (groupIds != null && !groupIds.isEmpty()) {
            String groupRange = groupIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
            builder.append("本次知识库范围：").append(groupRange).append(System.lineSeparator());
        }
        if (toolMode == AssistantToolMode.KB_SEARCH) {
            builder.append("知识库路由策略：")
                    .append(routeStrategy == null ? KbRouteStrategy.AUTO : routeStrategy)
                    .append(System.lineSeparator());
        }

        // 3. 按 toolMode 叠加任务层模板
        if (toolMode == AssistantToolMode.KB_SEARCH) {
            String kbPrompt = templateManager.render(KB_SEARCH_TEMPLATE, Map.of());
            if (!kbPrompt.isEmpty()) {
                builder.append(System.lineSeparator());
                builder.append(kbPrompt);
            }
        } else {
        builder.append(System.lineSeparator());
        builder.append("""
                    ## 工具使用规则（CHAT 模式）
                    1. 你拥有 webSearch 工具，可以搜索互联网获取实时信息。
                    2. 当用户的问题涉及实时信息、最新价格、新闻、动态、或任何你不确定的公开事实时，你**必须**调用 webSearch 工具查询。禁止仅在回复中描述"我要搜索"而不实际调用工具。
                    3. 调用工具后，基于工具返回的搜索结果回答用户问题。回答中保留标题和来源 URL。
                    4. webSearch 在一次回答中最多调用一次。工具返回结果后必须直接输出最终回答，禁止再次调用。
                    5. 禁止编造搜索结果、来源链接或实时数据。工具没有结果时如实说明。
                    6. 仅当当前上下文已完全足够回答（如简单闲聊、数学计算、翻译等无需外部信息的问题）时，才不调用工具。
                    """);
        }

        builder.append(System.lineSeparator()).append(System.lineSeparator());

        // 4. 用户输入分隔说明（文章依据：第二部分第2节）
        // 用户消息通过 agent.call(userMessage) 单独传入，与 system instruction 天然分离，
        // 这本身就是 Prompt Injection 防护的最佳实践（文章第五部分第5节）。
        // <knowledge> 标签内容由 Agent 运行时工具调用结果动态注入，不由此处预先填充。

        return builder.toString().trim();
    }

    /**
     * fallback：模板加载失败时使用内置基础 Prompt（保证可用性）。
     */
    private static String fallbackBasePrompt() {
        return """
                ## 角色
                你是一个可靠、克制、清晰的中文智能助手。

                ## 基本原则
                1. 优先直接回答用户问题，不要无意义铺垫。
                2. 不要编造事实、来源、经验、数据或结论；不确定时要明确说明不确定。
                3. 如果当前上下文已经足以完成回答，就直接输出最终答案。

                ## 最终要求
                如果当前上下文已经足以完成回答，就直接输出最终自然语言答案。
                不要输出内部思考、步骤标签、过程说明或任何与用户目标无关的内容。
                """;
    }
}
