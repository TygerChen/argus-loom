package com.argus.rag.excel.service;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelSheetChunkDelta;

import java.util.*;

/**
 * Sheet 增量合并纯函数。
 *
 * <p>把「以单元格(r,c)为 key 的 Delta 增量合并到基础 celldata JSON」这一无副作用的计算
 * 从 {@code ExcelSheetService} 中抽离，便于独立单元测试与复用；不依赖任何 DB / 事务。</p>
 */
final class SheetChunkMerger {

    private SheetChunkMerger() {
    }

    /**
     * 将增量列表合并进基础单元格 JSON，输出合并后的单元格数组。
     *
     * <p>规则：以 {@code "r_c"} 为唯一键（同键后序增量覆盖前序）；value 为空 / {@code {}}
     * / {@code null} 表示删除该单元格；否则以 {@code {r, c, v}} 形式写入。</p>
     *
     * @param baseJson 基础 celldata JSON（可为空）
     * @param deltas   待合并的增量列表，按序生效
     * @return 合并后的单元格数组
     */
    @SuppressWarnings("unchecked")
    public static List<Object> merge(String baseJson, List<ExcelSheetChunkDelta> deltas) {
        List<Object> base = (baseJson == null || baseJson.isEmpty())
                ? new ArrayList<>() : JsonCompat.parseArray(baseJson);
        Map<String, Map<String, Object>> cells = new LinkedHashMap<>();
        for (Object obj : base) {
            if (obj instanceof Map) {
                Map<String, Object> cell = (Map<String, Object>) obj;
                Object rObj = cell.get("r");
                Object cObj = cell.get("c");
                int r = rObj instanceof Number ? ((Number) rObj).intValue() : 0;
                int c = cObj instanceof Number ? ((Number) cObj).intValue() : 0;
                cells.put(r + "_" + c, cell);
            }
        }
        for (ExcelSheetChunkDelta delta : deltas) {
            String key = delta.getCellRow() + "_" + delta.getCellCol();
            String valueJson = delta.getCellValue();
            if (valueJson == null || valueJson.isBlank() || "{}".equals(valueJson.trim())
                    || "null".equals(valueJson.trim())) {
                cells.remove(key);
                continue;
            }
            Map<String, Object> payload = JsonCompat.parseObject(valueJson);
            if (payload == null || payload.isEmpty()) {
                cells.remove(key);
                continue;
            }
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("r", delta.getCellRow());
            cell.put("c", delta.getCellCol());
            cell.put("v", payload);
            cells.put(key, cell);
        }
        return new ArrayList<>(cells.values());
    }
}
