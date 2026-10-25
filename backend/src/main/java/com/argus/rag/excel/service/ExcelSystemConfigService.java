package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.excel.entity.ExcelSystemConfig;
import com.argus.rag.excel.mapper.ExcelSystemConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excel 系统策略配置：版本保留数量、自动快照间隔。
 *
 * <p>默认值：保留 10 个版本，单元格编辑自动快照间隔 30 分钟。</p>
 */
@Service
public class ExcelSystemConfigService {

    public static final String KEY_RETENTION_COUNT = "excel.version.retentionCount";
    public static final String KEY_AUTO_CELL_INTERVAL = "excel.version.autoCellIntervalMinutes";

    private final ExcelSystemConfigMapper configMapper;
    private final CurrentUserService currentUserService;

    public ExcelSystemConfigService(ExcelSystemConfigMapper configMapper,
                                    CurrentUserService currentUserService) {
        this.configMapper = configMapper;
        this.currentUserService = currentUserService;
    }

    public int getRetentionCount() {
        String value = get(KEY_RETENTION_COUNT, "10");
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    public int getAutoCellIntervalMinutes() {
        String value = get(KEY_AUTO_CELL_INTERVAL, "30");
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    public Map<String, Object> policy() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retentionCount", getRetentionCount());
        result.put("autoCellIntervalMinutes", getAutoCellIntervalMinutes());
        result.put("envRetentionCount", 10);
        result.put("envAutoCellIntervalMinutes", 30);
        result.put("retentionUpdatedBy", updatedBy(KEY_RETENTION_COUNT));
        result.put("retentionUpdatedTime", updatedTime(KEY_RETENTION_COUNT));
        return result;
    }

    public Map<String, Object> updatePolicy(int retentionCount, int autoCellIntervalMinutes) {
        String operator = null;
        try {
            operator = currentUserService.getRequiredCurrentUser().userCode();
        } catch (Exception ignored) {
        }
        set(KEY_RETENTION_COUNT, String.valueOf(Math.max(1, retentionCount)), operator);
        set(KEY_AUTO_CELL_INTERVAL, String.valueOf(Math.max(1, autoCellIntervalMinutes)), operator);
        return policy();
    }

    public String get(String key, String defaultValue) {
        ExcelSystemConfig config = selectByKey(key);
        return config == null || config.getConfigValue() == null ? defaultValue : config.getConfigValue();
    }

    public void set(String key, String value, String operator) {
        ExcelSystemConfig exist = selectByKey(key);
        if (exist == null) {
            ExcelSystemConfig config = new ExcelSystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setUpdatedBy(operator);
            configMapper.insert(config);
        } else {
            LambdaUpdateWrapper<ExcelSystemConfig> uw = new LambdaUpdateWrapper<>();
            uw.eq(ExcelSystemConfig::getConfigKey, key)
                    .set(ExcelSystemConfig::getConfigValue, value)
                    .set(ExcelSystemConfig::getUpdatedBy, operator);
            configMapper.update(null, uw);
        }
    }

    private ExcelSystemConfig selectByKey(String key) {
        QueryWrapper<ExcelSystemConfig> qw = new QueryWrapper<>();
        qw.eq("config_key", key).last("LIMIT 1");
        return configMapper.selectOne(qw);
    }

    private String updatedBy(String key) {
        ExcelSystemConfig config = selectByKey(key);
        return config == null ? null : config.getUpdatedBy();
    }

    private LocalDateTime updatedTime(String key) {
        ExcelSystemConfig config = selectByKey(key);
        return config == null ? null : config.getUpdatedTime();
    }
}
