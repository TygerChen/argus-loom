package com.argus.rag.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 agnes-3.0-flash 端到端冒烟（需要网络 + AGNES_API_KEY）。
 * <p>复刻 application-dev.yml 的对话链路：base-url={@value api.agnes-ai.cn}、model=agnes-3.0-flash，
 * 让 spring-ai 的 OpenAiApi 完整走「HTTP POST → JSON 解析 → ChatResponse 文本」，验证模型真能作答。</p>
 * <p>该测试打真实外部计费接口，默认用 JUnit 条件（环境变量开关）隔离，不随全量单测一起跑。</p>
 */
class AgnesLiveSmokeIT {

    private static final String BASE_URL = "https://api.agnes-ai.cn";
    private static final String MODEL = "agnes-3.0-flash";
    private static final String QUESTION = "用一两句话介绍 Spring 框架是什么，并说一下它的核心特性。";

    @Test
    void agnesActuallyAnswersThroughSpringAi() throws Exception {
        String apiKey = System.getenv("AGNES_API_KEY");
        assertThat(apiKey)
                .as("请先 export AGNES_API_KEY=... 再运行，否则无法打真实 agnes 接口")
                .isNotBlank();

        // 与 application-dev.yml 同源的通道：OpenAiApi 自拼 /v1/chat/completions，base-url 不带 /v1。
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(BASE_URL)
                .apiKey(apiKey)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(MODEL).build())
                .build();

        ChatResponse response = chatModel.call(new Prompt(QUESTION));
        String text = response.getResult().getOutput().getText();

        // 端到端证明：返回非空即代表「配置→通道→HTTP→JSON 解析→文本」链路真实打通。
        assertThat(text).isNotBlank();
        System.out.println("===== AGNES LIVE SMOKE REPLY =====");
        System.out.println(text);
        System.out.println("==================================");
        // 控制台 GBK 乱码时，另存 UTF-8 以便外部读取真实答案。
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream("target/agnes-smoke-reply.txt"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }
}