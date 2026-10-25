package com.argus.rag.excel.service;

import com.argus.rag.common.util.DateFormats;
import com.argus.rag.common.util.JsonCompat;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 大文件流式解析服务 — XSSFReader + SAX，内存恒定，支持合并单元格和格式化。
 * 输出与 ExcelParserService 完全兼容的 celldata JSON 格式。
 *
 * <p><b>SAX 是什么（05 篇的"一口一口吃巨无霸"）</b>：xlsx 本质是一堆 XML 文件
 * 打成的 ZIP。普通解析（DOM 风格）把整个 XML 读成内存对象树——文件多大内存多大；
 * SAX 是"推"式解析：XML 词法器一路往前读，遇到开始标签/结束标签就"推"给回调
 * （本类内部 Handler 的 startElement/endElement），处理完立刻扔。
 * <b>内存里永远只有当前这一行</b>，80 万行也扛得住。</p>
 *
 * <p><b>关键工程点</b>：共享字符串表（SharedStringsTable）——xlsx 为省体积把
 * 重复字符串集中存放，单元格里存的只是"第 N 号字符串"的索引，解析时查表还原；
 * 输出格式与全量解析器（ExcelParserService）完全一致，下游存储管线不关心
 * 文件是用哪个解析器进来的。<b>两种实现、一个契约</b>，这是策略模式的实际应用。</p>
 */
@Service
public class StreamingParserService {

    private static final Logger log = LoggerFactory.getLogger(StreamingParserService.class);
    private static final DateTimeFormatter DATE_FMT = DateFormats.DATE;

    public Map<Integer, ParsedSheet> parseStreaming(InputStream inputStream) throws Exception {
        Map<Integer, ParsedSheet> result = new LinkedHashMap<>();

        // P2-12：OPCPackage 用 try-with-resources，异常路径也确保句柄关闭，防句柄/文件泄漏。
        try (OPCPackage pkg = OPCPackage.open(inputStream)) {
            XSSFReader reader = new XSSFReader(pkg);
            org.apache.poi.xssf.model.SharedStrings sst = reader.getSharedStringsTable();
            var saxFactory = SAXParserFactory.newInstance();
            saxFactory.setNamespaceAware(true);
            var parser = saxFactory.newSAXParser().getXMLReader();

            int sheetIdx = 0;
            XSSFReader.SheetIterator sheetIter = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheetIter.hasNext()) {
                InputStream sheetStream = sheetIter.next();
                String sheetName = sheetIter.getSheetName();
                log.info("流式解析 Sheet[{}]: {}", sheetIdx, sheetName);

                SheetHandler handler = new SheetHandler(sst);
                parser.setContentHandler(handler);
                parser.parse(new InputSource(sheetStream));
                sheetStream.close();

                ParsedSheet ps = new ParsedSheet();
                ps.sheetName = sheetName;
                ps.rows = handler.getRows();
                ps.colCount = handler.maxCol;
                ps.mergeConfig = convertMergeConfig(handler.mergeConfig);
                ps.columnWidths = convertColumnWidths(handler.columnWidths);
                result.put(sheetIdx, ps);
                sheetIdx++;
            }
            log.info("流式解析完成，共 {} 个 Sheet", sheetIdx);
            return result;
        }
    }

    /** 将流式解析结果转为兼容 celldata JSON */
    public static List<Object> toCelldata(Map<Integer, Map<Integer, String>> rows) {
        List<Object> celldata = new ArrayList<>();
        for (var rowEntry : rows.entrySet()) {
            int r = rowEntry.getKey();
            for (var colEntry : rowEntry.getValue().entrySet()) {
                int c = colEntry.getKey();
                String val = colEntry.getValue();
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("r", r);
                cell.put("c", c);
                Map<String, Object> vObj = new LinkedHashMap<>();
                vObj.put("v", val);
                vObj.put("m", val);
                Map<String, Object> ct = new LinkedHashMap<>();
                ct.put("fa", "General");
                ct.put("t", "s");
                vObj.put("ct", ct);
                cell.put("v", vObj);
                celldata.add(cell);
            }
        }
        return celldata;
    }

    public static Map<String, Object> toMergeConfig(Map<String, Map<String, Object>> merges) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        merges.forEach(cfg::put);
        return cfg;
    }

    public static Map<String, Object> toColumnWidths(Map<Integer, Integer> widths) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        widths.forEach((k, v) -> cfg.put(String.valueOf(k), v));
        return cfg;
    }

    private static Map<String, Object> convertMergeConfig(Map<String, Map<String, Object>> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach(result::put);
        return result;
    }

    private static Map<String, Object> convertColumnWidths(Map<Integer, Integer> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    public static class ParsedSheet {
        public String sheetName;
        public Map<Integer, Map<Integer, String>> rows;
        public int colCount;
        public Map<String, Object> mergeConfig;
        public Map<String, Object> columnWidths;
    }

    /** SAX Handler：逐行解析单元格 + 合并单元格 + 列宽。 */
    private static class SheetHandler extends DefaultHandler {

        private final org.apache.poi.xssf.model.SharedStrings sst;
        private final Map<Integer, Map<Integer, String>> rows = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> mergeConfig = new LinkedHashMap<>();
        private final Map<Integer, Integer> columnWidths = new LinkedHashMap<>();
        private int maxCol = 0;
        private int maxRow = 0;

        private String cellRef;
        private String cellType;
        private String cellStyle;
        private boolean inV;
        private boolean inF;
        private boolean isFormula;
        private StringBuilder cellValue = new StringBuilder();
        private StringBuilder formulaText = new StringBuilder();

        // 合并单元格解析
        private boolean inMergeCell;
        private int mergeFirstRow, mergeLastRow, mergeFirstCol, mergeLastCol;

        SheetHandler(org.apache.poi.xssf.model.SharedStrings sst) {
            this.sst = sst;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            switch (qName) {
                case "c" -> {
                    cellRef = attrs.getValue("r");
                    cellType = attrs.getValue("t");
                    cellStyle = attrs.getValue("s");
                    cellValue.setLength(0);
                    isFormula = false;
                }
                case "v" -> inV = true;
                case "f" -> { inF = true; isFormula = true; formulaText.setLength(0); }
                case "mergeCell" -> {
                    inMergeCell = true;
                    String ref = attrs.getValue("ref");
                    if (ref != null) {
                        String[] parts = ref.split(":");
                        int[] tl = parseCellRef(parts[0]);
                        int[] br = parts.length > 1 ? parseCellRef(parts[1]) : tl;
                        mergeFirstRow = tl[0]; mergeLastRow = br[0];
                        mergeFirstCol = tl[1]; mergeLastCol = br[1];
                    }
                }
                case "col" -> {
                    String min = attrs.getValue("min");
                    String max = attrs.getValue("max");
                    String width = attrs.getValue("width");
                    if (min != null && width != null) {
                        double w = Double.parseDouble(width);
                        int colIdx = Integer.parseInt(min) - 1;
                        columnWidths.put(colIdx, (int) Math.round(w * 7.2));
                    }
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inV) cellValue.append(ch, start, length);
            if (inF) formulaText.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (qName) {
                case "v" -> inV = false;
                case "f" -> inF = false;
                case "mergeCell" -> {
                    if (inMergeCell) {
                        mergeConfig.put(mergeFirstRow + "_" + mergeFirstCol, createMergeEntry());
                        inMergeCell = false;
                    }
                }
                case "c" -> {
                    if (cellRef == null) break;
                    int[] pos = parseCellRef(cellRef);
                    int r = pos[0], c = pos[1];
                    maxRow = Math.max(maxRow, r);
                    maxCol = Math.max(maxCol, c + 1);

                    String val;
                    if (isFormula && formulaText.length() > 0) {
                        val = "=" + formulaText.toString();
                    } else {
                        String raw = cellValue.toString();
                        if ("s".equals(cellType) && !raw.isEmpty()) {
                            int idx = Integer.parseInt(raw);
                            val = sst.getItemAt(idx).getString();
                        } else {
                            val = raw;
                        }
                    }
                    if (!val.isEmpty()) {
                        rows.computeIfAbsent(r, k -> new LinkedHashMap<>()).put(c, val);
                    }
                }
            }
        }

        private Map<String, Object> createMergeEntry() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("r", mergeFirstRow);
            m.put("c", mergeFirstCol);
            m.put("rs", mergeLastRow - mergeFirstRow + 1);
            m.put("cs", mergeLastCol - mergeFirstCol + 1);
            return m;
        }

        private int[] parseCellRef(String ref) {
            String col = ref.replaceAll("\\d", "");
            String row = ref.replaceAll("\\D", "");
            int c = 0;
            for (int i = 0; i < col.length(); i++) c = c * 26 + (col.charAt(i) - 'A' + 1);
            return new int[]{Integer.parseInt(row) - 1, c - 1};
        }

        Map<Integer, Map<Integer, String>> getRows() { return rows; }
    }
}
