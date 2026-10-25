package com.argus.rag.config;

import com.argus.rag.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 查询当前可用 AI 模型的接口。
 */
@RestController
@RequestMapping("/api")
public class ModelController {

    @GetMapping("/models")
    public ApiResponse<List<ModelConfig.ModelOption>> listModels() {
        return ApiResponse.success(ModelConfig.TEXT_MODELS);
    }
}