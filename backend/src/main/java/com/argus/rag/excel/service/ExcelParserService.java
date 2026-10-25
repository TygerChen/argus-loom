package com.argus.rag.excel.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.util.DateFormats;
import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.mapper.ExcelSheetChunkMapper;
import com.argus.rag.excel.mapper.ExcelSheetMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Excel 解析引擎（全量模式）—— 把 xlsx 文件变成数据库里的 Sheet + Chunk。
 *
 * <p><b>与 StreamingParserService 的分工</b>（05 篇：两种解析模式）：本类用
 * POI 的 WorkbookFactory 全量加载，适合常规大小的文件——能拿到公式求值器、
 * 完整样式，代码也直白；几十 MB 的超大文件走 StreamingParserService（SAX 流式）。
 * 按输入规模选实现，是后端工程师的基本功。</p>
 *
 * <p><b>解析产出什么</b>：每个 Sheet 存一行元信息（行列数、合并单元格、
 * 列宽行宽——前端画表格骨架要用），单元格数据按 {@link #CHUNK_SIZE} 行一块
 * 存成稀疏 JSON（只存有值的格）。这套结构与 ExcelSheetService 的
 * "基数 + 增量"存储模型直接对接：解析产出的块就是初始基数。</p>
 *
 * <p><b>安全细节：压缩炸弹</b>。xlsx 是 ZIP 包，攻击者可以造一个
 * "50MB 压缩、解压 50GB" 的文件把内存打爆。ZipSecureFile.setMinInflateRatio(0.01)
 * 强制"解压后至少是压缩包的 1%"——解压膨胀超出 100 倍直接拒绝，
 * 这是处理用户上传压缩格式文件的标准防护。</p>
 */
@Service
public class ExcelParserService {

    private static final Logger log = LoggerFactory.getLogger(ExcelParserService.class);

    /** 每块包含的行数：解析切块和在线编辑定位（行号 / CHUNK_SIZE）都用它，改了会牵连存量数据。 */
    public static final int CHUNK_SIZE = 1000;

    private final ExcelSheetMapper sheetMapper;
    private final ExcelSheetChunkMapper chunkMapper;
    private final RedisChunkCacheService cacheService;

    public ExcelParserService(ExcelSheetMapper sheetMapper, ExcelSheetChunkMapper chunkMapper) {
        this(sheetMapper, chunkMapper, null);
    }

    /** 带分块缓存的构造（生产）：解析/修复会改写 chunk 行，必须同步失效缓存。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ExcelParserService(ExcelSheetMapper sheetMapper, ExcelSheetChunkMapper chunkMapper,
                              RedisChunkCacheService cacheService) {
        this.sheetMapper = sheetMapper;
        this.chunkMapper = chunkMapper;
        this.cacheService = cacheService;
    }

    @Transactional(rollbackFor = Exception.class)
    /**
     * 解析主入口：一个事务里完成"每个 Sheet 存元信息 + 单元格切块入库"。
     * 调用方（文档解析管线 / 财务导入）拿返回的 Sheet 列表继续后续流程。
     */
    public List<ExcelSheet> parseAndSave(InputStream is, ExcelDocument document) throws Exception {
        List<ExcelSheet> savedSheets = new ArrayList<>();

        // 压缩炸弹防护：解压后内容至少为压缩包的 1%（POI 默认 1%，此前被放宽到 0.1%，
        // 相当于允许 50MB 上传解压出 50GB 内存压力）。恢复到安全阈值。
        org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0.01);
        Workbook workbook = WorkbookFactory.create(is);

        int sheetTotal = workbook.getNumberOfSheets();
        log.info("开始解析文档 [{}], 共 {} 个 Sheet", document.getName(), sheetTotal);

        for (int si = 0; si < sheetTotal; si++) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(si);
            log.info("  → 解析 Sheet[{}]: {}", si, sheet.getSheetName());

            ExcelSheet sheetEntity = saveSheetMeta(sheet, document, si);
            savedSheets.add(sheetEntity);
            saveSheetChunks(sheet, workbook, sheetEntity);
        }
        workbook.close();

        log.info("文档 [{}] 解析完成，共 {} 个 Sheet", document.getName(), savedSheets.size());
        return savedSheets;
    }

    /**
     * 存 Sheet 元信息。合并单元格/列宽/行宽各自序列化成 JSON 单独存一列，
     * 又汇总进 config_json——前端（Univer）打开表格时按需取用。
     */
    private ExcelSheet saveSheetMeta(org.apache.poi.ss.usermodel.Sheet sheet,
                                      ExcelDocument document, int sheetIndex) {
        ExcelSheet sheetEntity = new ExcelSheet();
        sheetEntity.setDocumentId(document.getId());
        sheetEntity.setSheetIndex(sheetIndex);
        sheetEntity.setSheetName(sheet.getSheetName());
        sheetEntity.setActive(sheetIndex == 0 ? 1 : 0);
        sheetEntity.setStatus(1);

        int maxRow = sheet.getLastRowNum();
        int maxCol = calcMaxCol(sheet);
        sheetEntity.setTotalRows(maxRow + 1);
        sheetEntity.setTotalCols(maxCol);

        Map<String, Object> mergeConfig = buildMergeConfig(sheet);
        sheetEntity.setMergeConfigJson(JsonCompat.toJSONString(mergeConfig));

        Map<String, Object> columnLen = buildColumnLen(sheet, maxCol);
        sheetEntity.setColumnLenJson(JsonCompat.toJSONString(columnLen));

        Map<String, Object> rowLen = buildRowLen(sheet);
        sheetEntity.setRowLenJson(JsonCompat.toJSONString(rowLen));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("merge", mergeConfig);
        config.put("columnlen", columnLen);
        config.put("rowlen", rowLen);
        sheetEntity.setConfigJson(JsonCompat.toJSONString(config));

        sheetEntity.setChunkCount(0);
        sheetMapper.insert(sheetEntity);
        log.info("    Sheet 元信息已保存: id={}, rows={}, cols={}", sheetEntity.getId(), maxRow + 1, maxCol);
        return sheetEntity;
    }

    /**
     * 单元格切块写入。逐行遍历，攒满 CHUNK_SIZE 行（或到最后一行）就把缓冲区
     * 一次性序列化落库——内存里永远只有一块的数据量。
     * 公式在解析时求值：存的是"计算结果 + 公式原文"双份，
     * 前端显示结果、导出保留公式（05 篇 Q3：公式不重算，诚实展示原文）。
     */
    private void saveSheetChunks(org.apache.poi.ss.usermodel.Sheet sheet,
                                  org.apache.poi.ss.usermodel.Workbook workbook,
                                  ExcelSheet sheetEntity) {
        org.apache.poi.ss.usermodel.FormulaEvaluator evaluator =
                workbook.getCreationHelper().createFormulaEvaluator();
        int lastRowNum = sheet.getLastRowNum();

        List<Map<String, Object>> buffer = new ArrayList<>(CHUNK_SIZE * 10);
        int chunkIndex = 0;
        int chunkStartRow = 0;
        int chunkEndRow = 0;

        for (int rowIdx = 0; rowIdx <= lastRowNum; rowIdx++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(rowIdx);
            if (row == null) continue;

            for (org.apache.poi.ss.usermodel.Cell cell : row) {
                Map<String, Object> vObj = buildCellValue(cell, evaluator);
                if (vObj != null) {
                    Map<String, Object> cellItem = new LinkedHashMap<>();
                    cellItem.put("r", cell.getRowIndex());
                    cellItem.put("c", cell.getColumnIndex());
                    cellItem.put("v", vObj);
                    buffer.add(cellItem);
                }
            }

            chunkEndRow = rowIdx;

            boolean isChunkFull = (rowIdx - chunkStartRow + 1) >= CHUNK_SIZE;
            boolean isLastRow = (rowIdx == lastRowNum);

            if ((isChunkFull || isLastRow) && !buffer.isEmpty()) {
                ExcelSheetChunk chunk = new ExcelSheetChunk();
                chunk.setDocumentId(sheetEntity.getDocumentId());
                chunk.setSheetId(sheetEntity.getId());
                chunk.setChunkIndex(chunkIndex);
                chunk.setRowStart(chunkStartRow);
                chunk.setRowEnd(chunkEndRow);
                chunk.setCelldataJson(JsonCompat.toJSONString(buffer));
                chunkMapper.insert(chunk);

                log.debug("    Chunk[{}] 已写入: rows {}-{}, cellCount={}", chunkIndex, chunkStartRow, chunkEndRow, buffer.size());

                buffer.clear();
                chunkIndex++;
                chunkStartRow = rowIdx + 1;
            } else if (isChunkFull) {
                chunkStartRow = rowIdx + 1;
            }
        }

        ExcelSheet update = new ExcelSheet();
        update.setId(sheetEntity.getId());
        update.setChunkCount(chunkIndex);
        sheetMapper.updateById(update);
        sheetEntity.setChunkCount(chunkIndex);

        log.info("    Sheet [{}] 分块完成，共 {} 块", sheetEntity.getSheetName(), chunkIndex);
    }

    /**
     * 单个单元格 → 前端表格 JSON（LuckySheet/Univer 的 {r, c, v} 结构）。
     * 按类型分发：字符串 / 数字 / 日期（统一格式化）/ 公式（求值 + 原文）/ 布尔。
     * 空值返回 null，调用方据此跳过——这就是"稀疏存储"的源头。
     */
    private Map<String, Object> buildCellValue(org.apache.poi.ss.usermodel.Cell cell,
                                       org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        Map<String, Object> v = new LinkedHashMap<>();
        Map<String, Object> ct = new LinkedHashMap<>();

        try {
            switch (cell.getCellType()) {
                case STRING: {
                    String val = cell.getStringCellValue();
                    if (val == null || val.isEmpty()) return null;
                    v.put("v", val);
                    v.put("m", val);
                    ct.put("fa", "General");
                    ct.put("t", "s");
                    break;
                }
                case NUMERIC: {
                    if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                        // P3-04：统一用 DateFormats（DateTimeFormatter 线程安全），替代残余 SimpleDateFormat
                        String dateStr = cell.getDateCellValue().toInstant()
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate()
                                .format(DateFormats.DATE);
                        v.put("v", dateStr);
                        v.put("m", dateStr);
                        ct.put("fa", "yyyy-MM-dd");
                        ct.put("t", "d");
                    } else {
                        double num = cell.getNumericCellValue();
                        v.put("v", num);
                        v.put("m", formatNum(num));
                        ct.put("fa", "General");
                        ct.put("t", "n");
                    }
                    break;
                }
                case BOOLEAN: {
                    boolean b = cell.getBooleanCellValue();
                    v.put("v", b);
                    v.put("m", b ? "TRUE" : "FALSE");
                    ct.put("fa", "General");
                    ct.put("t", "b");
                    break;
                }
                case FORMULA: {
                    try {
                        org.apache.poi.ss.usermodel.CellValue cv = evaluator.evaluate(cell);
                        v.put("f", "=" + cell.getCellFormula());
                        if (cv != null && cv.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                            v.put("v", cv.getNumberValue());
                            v.put("m", formatNum(cv.getNumberValue()));
                        } else {
                            String sv = cv != null ? cv.getStringValue() : "";
                            v.put("v", sv);
                            v.put("m", sv);
                        }
                        ct.put("fa", "General");
                        ct.put("t", "n");
                    } catch (Exception fe) {
                        String raw = cell.getCellFormula();
                        v.put("v", "=" + raw);
                        v.put("m", "=" + raw);
                        ct.put("fa", "General");
                        ct.put("t", "s");
                    }
                    break;
                }
                case BLANK:
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }

        v.put("ct", ct);
        return v;
    }

    private int calcMaxCol(org.apache.poi.ss.usermodel.Sheet sheet) {
        int max = 0;
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            if (row.getLastCellNum() > max) {
                max = row.getLastCellNum();
            }
        }
        return max;
    }

    private Map<String, Object> buildMergeConfig(org.apache.poi.ss.usermodel.Sheet sheet) {
        Map<String, Object> mergeConfig = new LinkedHashMap<>();
        for (org.apache.poi.ss.util.CellRangeAddress range : sheet.getMergedRegions()) {
            String key = range.getFirstRow() + "_" + range.getFirstColumn();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("r", range.getFirstRow());
            item.put("c", range.getFirstColumn());
            item.put("rs", range.getLastRow() - range.getFirstRow() + 1);
            item.put("cs", range.getLastColumn() - range.getFirstColumn() + 1);
            mergeConfig.put(key, item);
        }
        return mergeConfig;
    }

    private Map<String, Object> buildColumnLen(org.apache.poi.ss.usermodel.Sheet sheet, int maxCol) {
        Map<String, Object> colWidths = new LinkedHashMap<>();
        try {
            for (int ci = 0; ci < maxCol; ci++) {
                double width = sheet.getColumnWidthInPixels(ci);
                colWidths.put(String.valueOf(ci), (int) Math.max(width, 72));
            }
        } catch (Exception ignored) {
            // 忽略某些列宽测量异常，不影响整体
        }
        return colWidths;
    }

    private Map<String, Object> buildRowLen(org.apache.poi.ss.usermodel.Sheet sheet) {
        Map<String, Object> rowHeights = new LinkedHashMap<>();
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            if (row == null) continue;
            float height = row.getHeightInPoints();
            if (height > 0) {
                rowHeights.put(String.valueOf(row.getRowNum()), Math.round(height / 0.75f));
            }
        }
        return rowHeights;
    }

    static String formatNum(double num) {
        if (num == (long) num) return String.valueOf((long) num);
        return String.valueOf(num);
    }

    /**
     * 用磁盘上的原始 Excel 文件回填数字格式。
     * 只修正存储值与原文件一致的数值单元格，已编辑/删除的单元格不会被覆盖。
     */
    @Transactional(rollbackFor = Exception.class)
    public int repairNumberFormats(ExcelDocument document) throws Exception {
        if (document == null || document.getId() == null || document.getFilePath() == null) {
            return 0;
        }
        Path source = Path.of(document.getFilePath());
        if (!Files.exists(source)) {
            throw new IOException("原始文件不存在: " + source);
        }

        QueryWrapper<ExcelSheet> sheetQuery = new QueryWrapper<>();
        sheetQuery.eq("document_id", document.getId()).eq("status", 1).orderByAsc("sheet_index");
        List<ExcelSheet> sheets = sheetMapper.selectList(sheetQuery);
        if (sheets.isEmpty()) {
            return 0;
        }

        int repaired = 0;
        try (InputStream in = Files.newInputStream(source);
             Workbook workbook = WorkbookFactory.create(in)) {
            org.apache.poi.ss.usermodel.FormulaEvaluator evaluator =
                    workbook.getCreationHelper().createFormulaEvaluator();
            for (ExcelSheet sheetMeta : sheets) {
                int sheetIndex = sheetMeta.getSheetIndex() == null ? -1 : sheetMeta.getSheetIndex();
                if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()) {
                    continue;
                }
                org.apache.poi.ss.usermodel.Sheet sourceSheet = workbook.getSheetAt(sheetIndex);
                QueryWrapper<ExcelSheetChunk> chunkQuery = new QueryWrapper<>();
                chunkQuery.eq("sheet_id", sheetMeta.getId()).orderByAsc("chunk_index");

                for (ExcelSheetChunk chunk : chunkMapper.selectList(chunkQuery)) {
                    if (chunk.getCelldataJson() == null || chunk.getCelldataJson().trim().isEmpty()) {
                        continue;
                    }
                    List<Object> cells = JsonCompat.parseArray(chunk.getCelldataJson());
                    if (cells == null || cells.isEmpty()) {
                        continue;
                    }
                    Map<String, Map<String, Object>> cellIndex = new HashMap<>();
                    for (int i = 0; i < cells.size(); i++) {
                        Object item = cells.get(i);
                        if (!(item instanceof Map)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> node = (Map<String, Object>) item;
                        if (!node.containsKey("r") || !node.containsKey("c")) {
                            continue;
                        }
                        int rVal = ((Number) node.get("r")).intValue();
                        int cVal = ((Number) node.get("c")).intValue();
                        cellIndex.put(rVal + "_" + cVal, node);
                    }

                    int startRow = chunk.getRowStart() == null ? 0 : chunk.getRowStart();
                    int endRow = chunk.getRowEnd() == null ? sourceSheet.getLastRowNum() : chunk.getRowEnd();
                    int repairedInChunk = 0;
                    for (int r = startRow; r <= Math.min(endRow, sourceSheet.getLastRowNum()); r++) {
                        org.apache.poi.ss.usermodel.Row row = sourceSheet.getRow(r);
                        if (row == null) {
                            continue;
                        }
                        for (org.apache.poi.ss.usermodel.Cell cell : row) {
                            Map<String, Object> stored = cellIndex.get(row.getRowNum() + "_" + cell.getColumnIndex());
                            if (stored == null) {
                                continue;
                            }
                            Map<String, Object> parsed = buildCellValue(cell, evaluator);
                            if (parsed != null && patchNumberFormat(stored, parsed)) {
                                repairedInChunk++;
                            }
                        }
                    }
                    if (repairedInChunk > 0) {
                        chunk.setCelldataJson(JsonCompat.toJSONString(cells));
                        chunkMapper.updateById(chunk);
                        repaired += repairedInChunk;
                    }
                }
            }
        }
        log.info("文档 [{}] 数字格式修复完成，共回填 {} 个单元格", document.getName(), repaired);
        if (repaired > 0 && cacheService != null) {
            // P1-05：chunk 行被就地改写 → 失效该文档的分块缓存
            cacheService.invalidateDocument(document.getId());
        }
        return repaired;
    }

    /** 仅当库里值未被用户改过且类型仍是数字时，覆盖原文件格式。 */
    @SuppressWarnings("unchecked")
    boolean patchNumberFormat(Map<String, Object> stored, Map<String, Object> parsed) {
        Object storedValueObj = stored == null ? null : stored.get("v");
        Object parsedValueObj = parsed == null ? null : parsed.get("v");
        if (!(storedValueObj instanceof Map) || !(parsedValueObj instanceof Map)) {
            return false;
        }
        Map<String, Object> storedValue = (Map<String, Object>) storedValueObj;
        Map<String, Object> parsedValue = (Map<String, Object>) parsedValueObj;
        Map<String, Object> storedCt = jsonObjectOrNull(storedValue.get("ct"));
        if (storedCt == null || !"n".equals(JsonCompat.getString(storedCt, "t"))) {
            return false;
        }
        Object storedRaw = storedValue.get("v");
        Object parsedRaw = parsedValue.get("v");
        if (!(storedRaw instanceof Number) || !(parsedRaw instanceof Number)) {
            return false;
        }
        if (storedValue.containsKey("f") != parsedValue.containsKey("f")) {
            return false;
        }
        if (storedValue.containsKey("f")) {
            if (!String.valueOf(storedValue.get("f")).equals(String.valueOf(parsedValue.get("f")))) {
                return false;
            }
        } else if (Double.compare(((Number) storedRaw).doubleValue(),
                ((Number) parsedRaw).doubleValue()) != 0) {
            return false;
        }
        Map<String, Object> parsedCt = jsonObjectOrNull(parsedValue.get("ct"));
        String parsedFa = parsedCt == null ? "General" : JsonCompat.getString(parsedCt, "fa");
        String parsedM = JsonCompat.getString(parsedValue, "m");
        if (parsedFa == null) parsedFa = "General";
        if (parsedM == null) parsedM = "";
        if (parsedFa.equals(JsonCompat.getString(storedCt, "fa"))
                && parsedM.equals(JsonCompat.getString(storedValue, "m"))) {
            return false;
        }
        Map<String, Object> targetCt = parsedCt == null ? new LinkedHashMap<>() : parsedCt;
        targetCt.put("t", "n");
        storedValue.put("ct", targetCt);
        storedValue.put("m", parsedM);
        return true;
    }

    private Map<String, Object> jsonObjectOrNull(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
