package com.argus.rag.finance.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.service.ExcelAuditService;
import com.argus.rag.finance.entity.FinanceReport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.argus.rag.common.config.ArgusRagProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 财务简报 PDF 生成：纯后端渲染，中文使用系统中文字体。
 *
 * <p><b>为什么后端渲染 PDF 而不是前端打印</b>：PDF 要作为正式留档发给领导，
 * 内容和排版必须"所有人看到的都一样"——前端打印依赖各人浏览器和打印机驱动。
 * 后端用 PDFBox 在服务器上渲染，输出确定的字节流。</p>
 *
 * <p><b>中文字体的坑（FONT_CANDIDATES 那一排）</b>：PDF 内置字体不含中文，
 * 必须加载系统字体文件。代码按候选列表依次尝试（Windows 的微软雅黑/宋体、
 * Linux 的 Noto/Droid），第一个找到的生效——同一份代码在 Windows 开发机
 * 和 Linux 服务器都能出正确的中文，这是"环境差异收口到配置式列表"的做法。</p>
 */
@Service
public class FinanceReportPdfService {

    private static final Logger log = LoggerFactory.getLogger(FinanceReportPdfService.class);

    private final ExcelAuditService auditService;

    @Value("${argus.rag.excel.upload-path:./upload}")
    private String uploadPath;

    private static final float MARGIN = 52;
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();

    private static final String[] FONT_CANDIDATES = {
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/msyhl.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/msjh.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"
    };

    public FinanceReportPdfService(ExcelAuditService auditService) {
        this(auditService, new ArgusRagProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FinanceReportPdfService(ExcelAuditService auditService,
                                   ArgusRagProperties argusProperties) {
        this.auditService = auditService;
        this.uploadPath = argusProperties.getUploadPath();
    }

    /** 生成 PDF 并返回本地绝对路径，同时写入审计日志。 */
    public String exportReportPdf(FinanceReport report,
                                  List<Map<String, Object>> payrollRows,
                                  Map<String, Object> overview) {
        Path dir = uploads().resolve("export_pdf");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BusinessException(ErrorCodes.FINANCE_PDF_FILE_FAILED, "创建 PDF 目录失败");
        }
        String fileName = "finance-report-" + report.getId() + "-"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".pdf";
        Path target = dir.resolve(fileName);
        try (PDDocument document = new PDDocument()) {
            renderDocument(document, report, payrollRows, overview);
            try (OutputStream out = Files.newOutputStream(target)) {
                document.save(out);
            }
        } catch (IOException e) {
            log.error("PDF 生成失败: reportId={}", report.getId(), e);
            throw new BusinessException(ErrorCodes.FINANCE_PDF_FILE_FAILED, "PDF 生成失败: " + e.getMessage());
        }
        auditService.record("FINANCE_REPORT_PDF", "finance_report",
                String.valueOf(report.getId()), "导出 PDF: " + fileName);
        return target.toAbsolutePath().toString();
    }

    /** 下载时读取 PDF 字节流。路径来自数据库存档，不存在时给明确业务错误码。 */
    public byte[] readPdf(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) throw new BusinessException(ErrorCodes.FINANCE_PDF_NOT_FOUND, "PDF 文件不存在");
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new BusinessException(ErrorCodes.FINANCE_PDF_FILE_FAILED, "读取 PDF 文件失败");
        }
    }

    public String fileNameFor(FinanceReport report) {
        return "finance-report-" + report.getId() + ".pdf";
    }

    private void renderDocument(PDDocument document, FinanceReport report,
                                List<Map<String, Object>> payrollRows,
                                Map<String, Object> overview) throws IOException {
        PDFont font = resolveFont(document);
        PageState state = new PageState(document);

        state.write(font, "财务经营简报", 15, true);
        state.write(font, 11, false);
        state.skip(16);

        state.write(font, "一、经营摘要", 12, false);
        if (overview == null || overview.isEmpty()) {
            state.write(font, "暂无经营汇总数据", 9, false);
        } else {
            for (Map.Entry<String, Object> entry : overview.entrySet()) {
                state.write(font, entry.getKey() + "：" + entry.getValue(), 9, false);
            }
        }
        state.skip(14);

        state.write(font, "二、人事薪酬台账（元）", 12, false);
        if (payrollRows == null || payrollRows.isEmpty()) {
            state.write(font, "当月暂无薪酬台账数据", 9, false);
        } else {
            state.write(font, "工号　　姓名　　基本工资　　绩效　　加班　　补贴　　实发", 8.5f, false);
            for (Map<String, Object> row : payrollRows) {
                state.write(font,
                        nvl(row.get("userCode")) + "　" + pad(nvl(row.get("displayName")), 9)
                                + "　" + nvl(row.get("baseSalary")) + "　"
                                + nvl(row.get("performanceBonus")) + "　"
                                + nvl(row.get("overtimePay")) + "　"
                                + nvl(row.get("allowance")) + "+" + nvl(row.get("subsidy"))
                                + "　" + nvl(row.get("netSalary")),
                        8.5f, false);
            }
        }
        state.skip(14);

        state.write(font, "三、AI 分析正文", 12, false);
        String markdown = report.getContentMarkdown() == null
                ? "（模型未生成正文）" : report.getContentMarkdown();
        for (String raw : markdown.split("\\r?\\n")) {
            String text = raw.replaceFirst("^#{1,6}\\s+", "").replaceAll("[*_`>#]", "");
            if (text.isBlank()) {
                state.skip(4);
            } else {
                state.write(font, text, 9, false);
            }
        }
        state.skip(10);
        state.write(font, "免责声明：本简报由 AI 根据财务与薪酬数据自动生成，仅作管理参考。", 8, false);
        state.close();
    }

    /** 轻量画布：管理当前页、当前流与 y 坐标，超页自动换页。 */
    private static final class PageState {
        private PDPage page;
        private PDPageContentStream stream;
        private float cursorY;
        private final PDDocument document;

        private PageState(PDDocument document) throws IOException {
            this.document = document;
            beginPage();
        }

        void write(PDFont font, String text, float fontSize, boolean centered)
                throws IOException {
            for (String line : wrap(text, font, fontSize, PAGE_WIDTH - MARGIN * 2)) {
                ensureSpace(font, fontSize);
                if (centered) {
                    float width = font.getStringWidth(line) * fontSize / 1000f;
                    stream.beginText();
                    stream.newLineAtOffset((PAGE_WIDTH - width) / 2, cursorY);
                    stream.showText(line);
                    stream.endText();
                } else {
                    stream.beginText();
                    stream.newLineAtOffset(MARGIN, cursorY);
                    stream.showText(line);
                    stream.endText();
                }
                cursorY -= fontSize * 1.5f;
            }
        }

        void write(PDFont font, float blankSpace, boolean ignored) {
            cursorY -= blankSpace;
        }

        void skip(float points) {
            cursorY -= points;
        }

        void close() throws IOException {
            stream.close();
        }

        private void ensureSpace(PDFont font, float fontSize) throws IOException {
            if (cursorY < 56) {
                stream.close();
                beginPage();
            }
            stream.setFont(font, fontSize);
        }

        private void beginPage() throws IOException {
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            cursorY = PAGE_HEIGHT - 52;
        }
    }

    private static List<String> wrap(String text, PDFont font, float fontSize, float maxWidth)
            throws IOException {
        if (text == null) return List.of("");
        String value = text.trim();
        if (font.getStringWidth(value) * fontSize / 1000f <= maxWidth) {
            return List.of(value);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (current.length() > 0
                    && font.getStringWidth(current.toString() + ch) * fontSize / 1000f > maxWidth) {
                lines.add(current.toString());
                current.setLength(0);
            }
            current.append(ch);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private String nvl(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String pad(String value, int width) {
        if (value == null) value = "";
        return value.length() >= width ? value
                : value + " ".repeat(width - value.length());
    }

    private PDFont resolveFont(PDDocument document) throws IOException {
        for (String candidate : FONT_CANDIDATES) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                try {
                    return PDType0Font.load(document, path.toFile());
                } catch (Exception e) {
                    log.debug("字体加载失败: {}", candidate);
                }
            }
        }
        throw new IOException("未找到可用的中文字体，请放置 msyh.ttc 或 simsun.ttc");
    }

    private Path uploads() {
        return Paths.get(uploadPath == null || uploadPath.isBlank()
                ? "./upload" : uploadPath).toAbsolutePath().normalize();
    }
}
