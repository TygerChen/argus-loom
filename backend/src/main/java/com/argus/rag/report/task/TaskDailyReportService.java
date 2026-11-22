package com.argus.rag.report.task;

import com.argus.rag.excel.pipeline.celldata.CellDataTable;
import com.argus.rag.excel.service.ExcelSheetService;
import com.argus.rag.finance.report.DingTalkWebhookClient;
import com.argus.rag.finance.report.entity.ReportDefinition;
import com.argus.rag.finance.report.entity.ReportExecution;
import com.argus.rag.finance.report.mapper.ReportDefinitionMapper;
import com.argus.rag.finance.report.mapper.ReportExecutionMapper;
import com.argus.rag.llm.ModelClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务进度日报服务 —— 通用日报引擎的实例 #2（在线表格数据源）。
 *
 * <p><b>流水线</b>：绑定协作表（documentId+sheetId 存 definition.pipelineJson）
 * → {@code ExcelSheetService} 读快照 → CellDataTable 矩阵化 → {@link TaskProgressScanner}
 * 聚合 → 简洁 Markdown 日报 → AI 一句话解读（fail-soft）→ report_execution 落库
 * → 钉钉推送（webhook 未配置自动 SKIPPED，只落库不报错）。</p>
 *
 * <p><b>与资金日报（实例 #1）的关系</b>：复用同一组 definition/execution 表、
 * 同一个钉钉通道；数据源从"上传文件"换成"在线协作表快照"——团队直接在
 * Excel 协作模块里维护任务表，日报自动跟进。</p>
 */
@Service
public class TaskDailyReportService {

    private static final Logger log = LoggerFactory.getLogger(TaskDailyReportService.class);
    private static final String REPORT_TYPE = "TASK_DAILY";
    private static final String DEFINITION_NAME = "任务进度日报";
    private static final int HEADER_ROW = 0;
    private static final int AI_DIGEST_MAX_CHARS = 2000;

    private final ReportDefinitionMapper definitionMapper;
    private final ReportExecutionMapper executionMapper;
    private final ExcelSheetService excelSheetService;
    private final com.argus.rag.excel.service.ExcelDocumentService excelDocumentService;
    private final DingTalkWebhookClient dingTalkClient;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final String aiBriefInstructions;

    public TaskDailyReportService(
            ReportDefinitionMapper definitionMapper,
            ReportExecutionMapper executionMapper,
            ExcelSheetService excelSheetService,
            com.argus.rag.excel.service.ExcelDocumentService excelDocumentService,
            DingTalkWebhookClient dingTalkClient,
            ModelClient modelClient,
            ObjectMapper objectMapper,
            @Value("${argus.rag.task-report.ai-instructions:你是团队任务日报解读助手。根据给定任务统计数据，用 2-3 句中文输出简报：整体进度判断、最需要领导关注的一点（逾期/阻塞/今日到期）、一句行动建议。只使用给定数字，不编造。}") String aiBriefInstructions
    ) {
        this.definitionMapper = definitionMapper;
        this.executionMapper = executionMapper;
        this.excelSheetService = excelSheetService;
        this.excelDocumentService = excelDocumentService;
        this.dingTalkClient = dingTalkClient;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.aiBriefInstructions = aiBriefInstructions;
    }

    /** 绑定数据源：任务表所在协作文档（取其首个活跃 Sheet）。 */
    public Map<String, Object> bindSource(Long ownerId, Long documentId) {
        ReportDefinition definition = getOrCreateDefinition(ownerId);
        com.argus.rag.excel.entity.ExcelDocument document = excelDocumentService.getById(documentId);
        List<com.argus.rag.excel.entity.ExcelSheet> sheets = excelSheetService.listSheetsByDocumentId(documentId);
        if (document == null || sheets == null || sheets.isEmpty()) {
            throw new IllegalArgumentException("文档不存在或没有 Sheet：" + documentId);
        }
        com.argus.rag.excel.entity.ExcelSheet sheet = sheets.stream()
                .filter(s -> s.getActive() == null || s.getActive() == 1)
                .findFirst()
                .orElse(sheets.get(0));
        Map<String, Object> pipeline = new LinkedHashMap<>();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "ONLINE_SHEET");
        source.put("documentId", documentId);
        source.put("sheetId", sheet.getId());
        source.put("documentName", document.getName());
        source.put("sheetName", sheet.getSheetName());
        pipeline.put("source", source);
        try {
            definition.setPipelineJson(objectMapper.writeValueAsString(pipeline));
        } catch (Exception e) {
            throw new IllegalStateException("绑定信息序列化失败", e);
        }
        definitionMapper.updateById(definition);
        return source;
    }

    /** 当前绑定（未绑定时返回空 Map）。 */
    public Map<String, Object> currentBinding(Long ownerId) {
        ReportDefinition definition = findDefinition(ownerId);
        if (definition == null || definition.getPipelineJson() == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(definition.getPipelineJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 生成（重跑覆盖同日执行记录）——资金日报同款状态机。 */
    public ReportExecution generate(LocalDate bizDate, Long ownerId) {
        long start = System.currentTimeMillis();
        ReportDefinition definition = getOrCreateDefinition(ownerId);
        ReportExecution execution = getOrCreateExecution(definition.getId(), bizDate);
        execution.setStatus(ReportExecution.STATUS_RUNNING);
        execution.setErrorMessage(null);
        execution.setPushStatus(null);
        execution.setPushDetail(null);
        execution.setResultJson(null);
        executionMapper.updateById(execution);

        try {
            Map<String, String> binding = readSourceBinding(definition);
            TaskDailyResult result = scanOnlineSheet(binding);
            String resultJson = objectMapper.writeValueAsString(result);
            String markdown = buildMarkdown(bizDate, result, binding);
            execution.setResultJson(resultJson);
            execution.setHtmlContent(markdown);

            String brief = generateAiBrief(result);
            execution.setAiBriefText(brief);

            DingTalkWebhookClient.PushResult push = dingTalkClient.sendMarkdown(
                    "📋 任务进度日报 " + bizDate, withBrief(markdown, brief));
            execution.setPushStatus(push.status());
            execution.setPushDetail(push.detail());
            execution.setStatus(ReportExecution.STATUS_SUCCEEDED);
            execution.setDurationMs(System.currentTimeMillis() - start);
            executionMapper.updateById(execution);
            return execution;
        } catch (Exception e) {
            log.error("任务日报生成失败: date={}, owner={}", bizDate, ownerId, e);
            execution.setStatus(ReportExecution.STATUS_FAILED);
            execution.setErrorMessage(e.getMessage());
            execution.setPushStatus(ReportExecution.PUSH_SKIPPED);
            execution.setPushDetail("生成失败，未推送");
            execution.setDurationMs(System.currentTimeMillis() - start);
            executionMapper.updateById(execution);
            return execution;
        }
    }

    /** 单日执行记录。 */
    public ReportExecution getByDate(Long ownerId, LocalDate bizDate) {
        ReportDefinition definition = findDefinition(ownerId);
        if (definition == null) {
            return null;
        }
        return executionMapper.selectOne(new LambdaQueryWrapper<ReportExecution>()
                .eq(ReportExecution::getDefinitionId, definition.getId())
                .eq(ReportExecution::getBizDate, bizDate)
                .last("LIMIT 1"));
    }

    /** 历史列表（最近优先）。 */
    public List<ReportExecution> listExecutions(Long ownerId, LocalDate from, LocalDate to) {
        ReportDefinition definition = findDefinition(ownerId);
        if (definition == null) {
            return List.of();
        }
        return executionMapper.selectList(new LambdaQueryWrapper<ReportExecution>()
                .eq(ReportExecution::getDefinitionId, definition.getId())
                .ge(ReportExecution::getBizDate, from)
                .le(ReportExecution::getBizDate, to)
                .orderByDesc(ReportExecution::getBizDate));
    }

    private ReportDefinition findDefinition(Long ownerId) {
        return definitionMapper.selectOne(new LambdaQueryWrapper<ReportDefinition>()
                .eq(ReportDefinition::getOwnerUserId, ownerId)
                .eq(ReportDefinition::getReportType, REPORT_TYPE)
                .last("LIMIT 1"));
    }

    private ReportDefinition getOrCreateDefinition(Long ownerId) {
        ReportDefinition existing = findDefinition(ownerId);
        if (existing != null) {
            return existing;
        }
        ReportDefinition definition = new ReportDefinition();
        definition.setOwnerUserId(ownerId);
        definition.setName(DEFINITION_NAME);
        definition.setReportType(REPORT_TYPE);
        definition.setPipelineJson("{}");
        definition.setEnabled(true);
        definitionMapper.insert(definition);
        return definition;
    }

    private ReportExecution getOrCreateExecution(Long definitionId, LocalDate bizDate) {
        ReportExecution existing = executionMapper.selectOne(new LambdaQueryWrapper<ReportExecution>()
                .eq(ReportExecution::getDefinitionId, definitionId)
                .eq(ReportExecution::getBizDate, bizDate)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        ReportExecution execution = new ReportExecution();
        execution.setDefinitionId(definitionId);
        execution.setBizDate(bizDate);
        executionMapper.insert(execution);
        return execution;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readSourceBinding(ReportDefinition definition) {
        try {
            Map<String, Object> pipeline = objectMapper.readValue(definition.getPipelineJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Map<String, Object> source = (Map<String, Object>) pipeline.get("source");
            if (source == null || source.get("sheetId") == null) {
                throw new IllegalStateException("尚未绑定任务表，请先在任务日报页绑定协作表");
            }
            Map<String, String> out = new LinkedHashMap<>();
            out.put("documentId", String.valueOf(source.get("documentId")));
            out.put("sheetId", String.valueOf(source.get("sheetId")));
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("绑定信息解析失败", e);
        }
    }

    /** 在线表格快照 → 表头映射行（任务表 5 列）。 */
    private List<Map<String, String>> loadRows(Map<String, String> binding) {
        Long sheetId = Long.valueOf(binding.get("sheetId"));
        List<com.argus.rag.excel.entity.ExcelSheetChunk> chunks = excelSheetService.listChunksBySheetId(sheetId);
        CellDataTable table = CellDataTable.fromChunks(chunks);
        Map<Integer, String> headers = new LinkedHashMap<>();
        for (Map.Entry<Integer, Object> cell : table.rowAt(HEADER_ROW).entrySet()) {
            String header = CellDataTable.valueAsText(cell.getValue());
            if (header != null && !header.isBlank()) {
                headers.put(cell.getKey(), header.trim());
            }
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (Integer rowIndex : table.rowIndexes()) {
            if (rowIndex <= HEADER_ROW) {
                continue;
            }
            Map<Integer, Object> row = table.rowAt(rowIndex);
            Map<String, String> mapped = new LinkedHashMap<>();
            boolean hasContent = false;
            for (Map.Entry<Integer, String> header : headers.entrySet()) {
                String value = CellDataTable.valueAsText(row.get(header.getKey()));
                if (value != null && !value.isBlank()) {
                    hasContent = true;
                }
                mapped.put(header.getValue(), value == null ? "" : value);
            }
            if (hasContent) {
                rows.add(mapped);
            }
        }
        return rows;
    }

    private TaskDailyResult scanOnlineSheet(Map<String, String> binding) {
        return TaskProgressScanner.scan(loadRows(binding), LocalDate.now());
    }

    /** AI 一句话解读（fail-soft：模型失败返回空——日报主体不依赖模型）。 */
    private String generateAiBrief(TaskDailyResult result) {
        try {
            String digest = result.digest();
            if (digest.length() > AI_DIGEST_MAX_CHARS) {
                digest = digest.substring(0, AI_DIGEST_MAX_CHARS);
            }
            com.argus.rag.llm.ChatResult chatResult = modelClient.chat(
                    List.of(new ModelClient.ChatMessage("user", digest)),
                    aiBriefInstructions);
            return chatResult.content() == null ? "" : chatResult.content().trim();
        } catch (Exception e) {
            log.warn("任务日报 AI 解读失败（不影响日报主体）: {}", e.getMessage());
            return "";
        }
    }

    /** 简洁日报正文（钉钉 Markdown + 前端展示共用）。 */
    private String buildMarkdown(LocalDate bizDate, TaskDailyResult result, Map<String, String> binding) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 📋 任务进度日报 ").append(bizDate).append('\n');
        sb.append("**总任务 ").append(result.total()).append("**：✅ 已完成 ")
                .append(status(result, TaskProgressScanner.STATUS_DONE))
                .append(" ｜ 🔄 进行中 ").append(status(result, TaskProgressScanner.STATUS_IN_PROGRESS))
                .append(" ｜ ⏸ 未开始 ").append(status(result, TaskProgressScanner.STATUS_NOT_STARTED))
                .append(" ｜ 🚫 阻塞 ").append(status(result, TaskProgressScanner.STATUS_BLOCKED))
                .append('\n');
        if (!result.dueToday().isEmpty()) {
            sb.append("**⏰ 今日到期**：");
            for (TaskDailyResult.TaskItem item : result.dueToday()) {
                sb.append(item.name()).append("（").append(item.owner()).append("） ");
            }
            sb.append('\n');
        }
        if (!result.overdue().isEmpty()) {
            sb.append("**🚨 已逾期**：");
            for (TaskDailyResult.TaskItem item : result.overdue()) {
                sb.append(item.name()).append("（").append(item.owner()).append("，截止 ")
                        .append(item.dueDate()).append("） ");
            }
            sb.append('\n');
        }
        sb.append("**按人**：");
        for (TaskDailyResult.OwnerStat stat : result.ownerSummary()) {
            sb.append(stat.owner()).append(" 共").append(stat.total())
                    .append("(完").append(stat.done()).append("/进").append(stat.inProgress())
                    .append("/阻").append(stat.blocked()).append(") ");
        }
        sb.append('\n');
        if (!result.overdue().isEmpty()) {
            sb.append("**阻塞/逾期详情**：\n");
            for (TaskDailyResult.TaskItem item : result.overdue()) {
                sb.append("- ").append(item.name()).append("（").append(item.owner()).append("）")
                        .append(item.note() == null || item.note().isBlank() ? "" : " — " + item.note()).append('\n');
            }
        }
        String sourceName = binding.getOrDefault("documentName", "");
        if (!sourceName.isBlank()) {
            sb.append("\n> 数据源：").append(sourceName).append("（在线协作表）");
        }
        return sb.toString();
    }

    private String withBrief(String markdown, String brief) {
        if (brief == null || brief.isBlank()) {
            return markdown;
        }
        return markdown + "\n\n> 🤖 **AI 解读**：" + brief;
    }

    private int status(TaskDailyResult result, String status) {
        return result.byStatus().getOrDefault(status, 0);
    }
}
