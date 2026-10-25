package com.argus.rag.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / SpringDoc OpenAPI 配置。
 * 接口文档地址：http://localhost:10001/doc.html
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI argusOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("数据洞察中心 API")
                        .description("Argus 企业级智能知识平台 — 融合 RAG 与 AI Agent 技术")
                        .version("1.0.0"));
    }

    @Bean
    GroupedOpenApi excelGroup() {
        return GroupedOpenApi.builder()
                .group("Excel 编辑器")
                .displayName("Excel 编辑器")
                .pathsToMatch("/api/excel/**")
                .build();
    }
}
