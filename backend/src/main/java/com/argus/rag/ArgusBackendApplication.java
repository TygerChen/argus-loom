package com.argus.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 后端服务的 Spring Boot 启动类。
 * <p>聚合三个能力：{@code @EnableAsync} 开启异步、{@code @EnableScheduling} 开启定时任务、{@code @EnableRetry} 开启重试；通过 {@code @MapperScan} 自动扫描 mapper 包与长期记忆（longterm）包下的 MyBatis-Plus Mapper 接口，让它们启动即被 Spring 管理。</p>
 * <p>运行 {@code main} 即可拉起整个服务，是后端所有逻辑的入口。</p>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
@MapperScan({"com.argus.rag.**.mapper", "com.argus.rag.**.longterm"})
public class ArgusBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArgusBackendApplication.class, args);
    }
}
