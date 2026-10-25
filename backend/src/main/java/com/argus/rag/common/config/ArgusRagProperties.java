package com.argus.rag.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Excel 模块共享配置收口（`argus.rag.excel.*`）。
 *
 * <p>背景：上传路径等键此前在 5 个类里用 {@code @Value("${argus.rag.excel.upload-path:./upload}")}
 * 散落读取，既容易写错层级（P1-07 教训），也无法集中校验/改名。现在收敛为单一
 * {@link ConfigurationProperties} bean：所有 Excel 相关服务统一注入本类读取。</p>
 *
 * <p><b>测试兼容</b>：既有单元测试通过反射重定向各 Service 的 {@code uploadPath} 字段到临时目录。
 * 为不破坏这些测试，本类保留一个同名字段做委托——生产由 Spring 绑定 {@code argus.rag.excel.upload-path}，
 * 测试可直接反射改写本 bean 的字段（或经 {@link #setUploadPath(String)}），对所有注入方生效。</p>
 */
@Component
@ConfigurationProperties(prefix = "argus.rag.excel")
public class ArgusRagProperties {

    /** Excel 上传/导出落盘根目录（解析后写入相对/绝对路径字符串）。 */
    private String uploadPath = "./upload";

    public String getUploadPath() {
        return uploadPath;
    }

    public void setUploadPath(String uploadPath) {
        this.uploadPath = uploadPath;
    }

    /** 导出/上传共用的解析方法：把配置值规整为绝对路径（调用方负责建目录）。 */
    public java.nio.file.Path resolveUploadPath() {
        return java.nio.file.Path.of(uploadPath).toAbsolutePath().normalize();
    }
}
