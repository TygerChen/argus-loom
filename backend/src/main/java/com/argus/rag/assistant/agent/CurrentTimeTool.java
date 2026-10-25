package com.argus.rag.assistant.agent;

import com.argus.rag.common.util.DateFormats;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

/**
 * 当前时间工具——让 Agent 能回答"现在几点"。
 */
@Component
public class CurrentTimeTool {

    @Tool(description = "获取当前日期和时间（含时区）")
    public String getCurrentTime() {
        return ZonedDateTime.now().format(DateFormats.DATE_TIME_Z);
    }
}