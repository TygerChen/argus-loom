package com.argus.rag.config;

import java.util.List;

/**
 * 已对照 Agnes AI API（GET /models）核验过的可用模型清单。
 * 核验时间：2026-09-16。
 */
public final class ModelConfig {

    private ModelConfig() {}

    public static final String DEFAULT_MODEL = "agnes-3.0-flash";

    public static final List<ModelOption> TEXT_MODELS = List.of(
            new ModelOption("agnes-3.0-flash", "Agnes 3.0 Flash", "Fast, cost-effective model for general use", "text", true),
            new ModelOption("agnes-2.5-flash", "Agnes 2.5 Flash", "Lightweight fast model", "text", false),
            new ModelOption("agnes-2.5-pro", "Agnes 2.5 Pro", "High-quality professional model", "text", false),
            new ModelOption("agnes-2.5-pro-alpha", "Agnes 2.5 Pro Alpha", "Alpha version of professional model", "text", false),
            new ModelOption("agnes-2.5-pro-beta", "Agnes 2.5 Pro Beta", "Beta version of professional model", "text", false),
            new ModelOption("agnes-2.0-flash", "Agnes 2.0 Flash", "Older fast model", "text", false)
    );

    public static final List<ModelOption> IMAGE_MODELS = List.of(
            new ModelOption("agnes-image-2.1-flash", "Agnes Image 2.1 Flash", "Fast image generation", "image", false),
            new ModelOption("agnes-image-2.5-flash", "Agnes Image 2.5 Flash", "Latest fast image generation", "image", false)
    );

    public static final List<ModelOption> VIDEO_MODELS = List.of(
            new ModelOption("agnes-video-v2.0", "Agnes Video V2.0", "Video generation model", "video", false),
            new ModelOption("agnes-video-2.5", "Agnes Video 2.5", "Advanced video generation", "video", false),
            new ModelOption("agnes-video-2.5-flash", "Agnes Video 2.5 Flash", "Fast video generation", "video", false)
    );

    public static final List<ModelOption> ALL_MODELS = List.of(
            new ModelOption("agnes-3.0-flash", "Agnes 3.0 Flash", "Fast, cost-effective model for general use", "text", true),
            new ModelOption("agnes-2.5-flash", "Agnes 2.5 Flash", "Lightweight fast model", "text", false),
            new ModelOption("agnes-2.5-pro", "Agnes 2.5 Pro", "High-quality professional model", "text", false),
            new ModelOption("agnes-2.5-pro-alpha", "Agnes 2.5 Pro Alpha", "Alpha version of professional model", "text", false),
            new ModelOption("agnes-2.5-pro-beta", "Agnes 2.5 Pro Beta", "Beta version of professional model", "text", false),
            new ModelOption("agnes-2.0-flash", "Agnes 2.0 Flash", "Older fast model", "text", false),
            new ModelOption("agnes-image-2.1-flash", "Agnes Image 2.1 Flash", "Fast image generation", "image", false),
            new ModelOption("agnes-image-2.5-flash", "Agnes Image 2.5 Flash", "Latest fast image generation", "image", false),
            new ModelOption("agnes-video-v2.0", "Agnes Video V2.0", "Video generation model", "video", false),
            new ModelOption("agnes-video-2.5", "Agnes Video 2.5", "Advanced video generation", "video", false),
            new ModelOption("agnes-video-2.5-flash", "Agnes Video 2.5 Flash", "Fast video generation", "video", false)
    );

    public record ModelOption(String id, String displayName, String description, String category, boolean isDefault) {}

    public static ModelOption findByModelName(String modelName) {
        return ALL_MODELS.stream()
                .filter(m -> m.id().equals(modelName))
                .findFirst()
                .orElse(TEXT_MODELS.get(0));
    }
}