package com.argus.rag.excel.pipeline.celldata;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单元格数据二维表缓存（对 {@code celldata} 数组的矩阵封装）。
 *
 * <p>承载「把 chunk 里的 {@code [{"r","c","v"}]} 扁平化并还原成 行×列 矩阵」这一高频、
 * 且曾在多个类里重复出现的职责：{@code TableIngestionService}/{@code TableSchemaService}
 * 原本各自实现 {@code flattenCells + buildMatrix + extractValueAsString}，统一收敛到本类。</p>
 *
 * <p>健壮性：单个 chunk 解析失败不再「静默吞掉（注释着 skip）」，改为记录 warn 并继续，
 * 既不中断整个查询，也让坏数据可被观测。</p>
 */
public final class CellDataTable {

    private static final Logger log = LoggerFactory.getLogger(CellDataTable.class);

    /** 行号(0起始) → 列号(0起始) → 单元格原始值（v 字段，可能为 String/Number/Map） */
    private final Map<Integer, Map<Integer, Object>> rows;
    private final int minRow;
    private final int maxRow;
    private final int maxCol;

    private CellDataTable(Map<Integer, Map<Integer, Object>> rows) {
        this.rows = rows;
        this.minRow = rows.keySet().stream().mapToInt(Integer::intValue).min().orElse(0);
        this.maxRow = rows.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        this.maxCol = rows.values().stream()
                .flatMap(m -> m.keySet().stream())
                .mapToInt(Integer::intValue).max().orElse(-1);
    }

    /**
     * 从分块构建矩阵。坏 chunk 打 warn 后跳过，不中断整体解析。
     */
    public static CellDataTable fromChunks(List<ExcelSheetChunk> chunks) {
        Map<Integer, Map<Integer, Object>> rows = new HashMap<>();
        for (ExcelSheetChunk chunk : chunks) {
            try {
                List<Object> arr = JsonCompat.parseArray(chunk.getCelldataJson());
                if (arr == null || arr.isEmpty()) continue;
                for (int i = 0; i < arr.size(); i++) {
                    Object item = arr.get(i);
                    if (item instanceof Map) {
                        merge(rows, (Map<String, Object>) item);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Chunk[{}] 单元格数据解析失败: {}", chunk.getId(), e.getMessage());
            }
        }
        return new CellDataTable(rows);
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<Integer, Map<Integer, Object>> rows, Map<String, Object> cell) {
        int r = ((Number) cell.get("r")).intValue();
        int c = ((Number) cell.get("c")).intValue();
        rows.computeIfAbsent(r, k -> new HashMap<>()).put(c, cell.get("v"));
    }

    /** 第 r 行的完整行数据（列号→值）；行不存在返回空。 */
    public Map<Integer, Object> rowAt(int r) {
        return rows.getOrDefault(r, Map.of());
    }

    /** 第 r 行第 c 列单元格值；不存在返回 null。 */
    public Object valueAt(int r, int c) {
        Map<Integer, Object> row = rows.get(r);
        return row == null ? null : row.get(c);
    }

    public boolean hasRow(int r) {
        return rows.containsKey(r);
    }

    public Collection<Integer> rowIndexes() {
        return rows.keySet();
    }

    public int rows() {
        return rows.size();
    }

    public int minRow() {
        return minRow;
    }

    public int maxRow() {
        return maxRow;
    }

    /** 单格值归一化为文本：Map 优先取 {v},次取 {m}；否则 tostring。 */
    public static String valueAsText(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map vo) {
            Object val = vo.get("v");
            return val != null ? val.toString() : JsonCompat.getString((Map<?, ?>) vo, "m");
        }
        return raw.toString();
    }
}
