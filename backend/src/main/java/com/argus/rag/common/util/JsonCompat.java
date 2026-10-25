package com.argus.rag.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Fastjson → Jackson 迁移兼容层。
 * 提供 {@code parseObject} / {@code parseArray} / {@code toJSONString} 静态方法，
 * 内部委托 Jackson {@link ObjectMapper}，返回标准 {@link Map} / {@link List}，
 * 逐步替换 {@code com.alibaba.fastjson.JSON/JSONObject/JSONArray} 的使用。
 */
public final class JsonCompat {

    private static final Logger log = LoggerFactory.getLogger(JsonCompat.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCompat() {}

    /** 将 JSON 字符串解析为 Map（等价于 fastjson {@code JSON.parseObject}）。 */
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> result = MAPPER.readValue(json, new TypeReference<>() {});
            return result == null ? new LinkedHashMap<>() : result;
        } catch (Exception e) {
            log.warn("JSON parseObject 解析失败，返回空 Map（输入前缀: {}）: {}",
                    json.substring(0, Math.min(json.length(), 64)), e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 将 JSON 字符串解析为 List（等价于 fastjson {@code JSON.parseArray}）。 */
    public static List<Object> parseArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<Object> result = MAPPER.readValue(json, new TypeReference<>() {});
            return result == null ? new ArrayList<>() : result;
        } catch (Exception e) {
            log.warn("JSON parseArray 解析失败，返回空 List（输入前缀: {}）: {}",
                    json.substring(0, Math.min(json.length(), 64)), e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 将对象序列化为 JSON 字符串（等价于 fastjson {@code JSON.toJSONString}）。 */
    public static String toJSONString(Object obj) {
        if (obj == null) return "null";
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败，降级为 toString（对象类型: {}）: {}",
                    obj.getClass().getName(), e.getMessage());
            return String.valueOf(obj);
        }
    }

    /** 从 Map 中安全获取字符串值。 */
    public static String getString(Map<?, ?> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    /** 从 Map 中安全获取嵌套 Map。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getJSONObject(Map<?, ?> map, String key) {
        if (map == null) return new LinkedHashMap<>();
        Object v = map.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return new LinkedHashMap<>();
    }

    /** 从 Map 中安全获取嵌套 List。 */
    public static List<Object> getJSONArray(Map<?, ?> map, String key) {
        if (map == null) return new ArrayList<>();
        Object v = map.get(key);
        if (v instanceof List) return (List<Object>) v;
        return new ArrayList<>();
    }
}
