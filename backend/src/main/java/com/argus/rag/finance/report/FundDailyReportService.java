package com.argus.rag.finance.report;

import com.argus.rag.common.config.ArgusRagProperties;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.finance.report.entity.ReportDefinition;
import com.argus.rag.finance.report.entity.ReportExecution;
import com.argus.rag.finance.report.mapper.ReportDefinitionMapper;
import com.argus.rag.finance.report.mapper.ReportExecutionMapper;
import com.argus.rag.finance.report.model.FundDailyResult;
import com.argus.rag.finance.report.model.FundGroupResult;
import com.argus.rag.finance.report.model.FundReportConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 资金日报编排服务 —— 「上传 Excel → 扫描计算 → 渲染归档 → 钉钉推送」流水线。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>幂等</b>：一定义一基准日一行执行记录，重跑覆盖（先清后写），随时手动重新生成；</li>
 *   <li><b>同源</b>：result_json 是看板与钉钉推送的唯一数据源，不存在两套口径；</li>
 *   <li><b>fail-open 推送</b>：钉钉凭据缺失 → SKIPPED 只落库；勾稽未通过 → 拒绝推送
 *       （错数宁可不发，发了就是资金事故）；</li>
 *   <li><b>文件即数据源</b>：每用户一个目录，文件名解析公司名；同名文件重传即覆盖
 *       （「维护」语义），旧文件删除即退出合并范围。</li>
 * </ul>
 */
@Service
public class FundDailyReportService {

    private static final Logger log = LoggerFactory.getLogger(FundDailyReportService.class);

    private static final String REPORT_NAME = "资金日报";
    private static final String REPORT_TYPE = "FUND_DAILY";
    private static final List<String> ALLOWED_SUFFIXES = List.of(".xlsx", ".xls", ".xlsm");

    private final ReportDefinitionMapper definitionMapper;
    private final ReportExecutionMapper executionMapper;
    private final FundWorkbookScanner scanner;
    private final FundReportHtmlRenderer renderer;
    private final DingTalkWebhookClient dingTalkClient;
    private final ObjectMapper objectMapper;
    private final Path reportRoot;

    public FundDailyReportService(ReportDefinitionMapper definitionMapper,
                                  ReportExecutionMapper executionMapper,
                                  FundWorkbookScanner scanner,
                                  FundReportHtmlRenderer renderer,
                                  DingTalkWebhookClient dingTalkClient,
                                  ObjectMapper objectMapper,
                                  ArgusRagProperties argusProperties) {
        this.definitionMapper = definitionMapper;
        this.executionMapper = executionMapper;
        this.scanner = scanner;
        this.renderer = renderer;
        this.dingTalkClient = dingTalkClient;
        this.objectMapper = objectMapper;
        this.reportRoot = argusProperties.resolveUploadPath().resolve("fund-reports");
    }

    /** 上传资金报表 Excel：按用户目录归档，同名覆盖（维护语义）。 */
    public Map<String, Object> upload(MultipartFile file, Long ownerId) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new BusinessException("文件名为空");
        }
        // 路径穿越防护：只取文件名末段，丢弃任何目录部分（../、..\ 等），与 deleteFile 的消毒口径对称
        String safeName = Path.of(original).getFileName().toString();
        if (safeName.isBlank() || ".".equals(safeName) || "..".equals(safeName)) {
            throw new BusinessException("非法文件名");
        }
        String lower = safeName.toLowerCase();
        if (ALLOWED_SUFFIXES.stream().noneMatch(lower::endsWith)) {
            throw new BusinessException("仅支持 .xlsx/.xls/.xlsm 文件");
        }
        try {
            Path dir = reportRoot.resolve(String.valueOf(ownerId));
            Files.createDirectories(dir);
            Path target = dir.resolve(safeName).normalize();
            // 纵深防御：归一化后最终路径必须仍落在 reportRoot 内（防未来回归）
            if (!target.startsWith(reportRoot)) {
                throw new BusinessException("非法文件路径");
            }
            file.transferTo(target.toAbsolutePath());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fileName", safeName);
            data.put("size", file.getSize());
            data.put("company", new FundWorkbookScanner().parseCompanyName(safeName, defaultConfig()));
            return data;
        } catch (IOException e) {
            throw new BusinessException("文件保存失败: " + e.getMessage());
        }
    }

    /** 删除数据源文件（退出合并范围）。 */
    public boolean deleteFile(String fileName, Long ownerId) {
        Path target = reportRoot.resolve(String.valueOf(ownerId)).resolve(fileName).normalize();
        if (!target.startsWith(reportRoot) || !Files.exists(target)) {
            return false;
        }
        try {
            Files.delete(target);
            return true;
        } catch (IOException e) {
            throw new BusinessException("文件删除失败: " + e.getMessage());
        }
    }

    /** 列出当前用户已上传的报表文件。 */
    public List<Map<String, Object>> listFiles(Long ownerId) {
        Path dir = reportRoot.resolve(String.valueOf(ownerId));
        if (!Files.exists(dir)) {
            return List.of();
        }
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fileName", p.getFileName().toString());
                try {
                    item.put("size", Files.size(p));
                } catch (IOException e) {
                    item.put("size", 0);
                }
                files.add(item);
            });
        } catch (IOException e) {
            log.warn("读取报表目录失败: {}", e.getMessage());
        }
        return files;
    }

    /**
     * 生成/重跑指定基准日的资金日报：扫描 → 计算 → 渲染 → 勾稽 → 推送 → 落库。
     * 全程同步（个人量级秒级完成），失败信息写入执行记录而非直接抛出（页面可诊断）。
     */
    public ReportExecution generate(LocalDate bizDate, Long ownerId) {
        long start = System.currentTimeMillis();
        ReportDefinition definition = getOrCreateDefinition(ownerId);
        FundReportConfig config = loadConfig(definition);
        ReportExecution execution = getOrCreateExecution(definition.getId(), bizDate);
        execution.setStatus(ReportExecution.STATUS_RUNNING);
        execution.setErrorMessage(null);
        execution.setPushStatus(null);
        execution.setPushDetail(null);
        execution.setResultJson(null);
        execution.setHtmlContent(null);
        executionMapper.updateById(execution);

        try {
            FundDailyResult result = scanUserFiles(ownerId, bizDate, config);
            String resultJson = objectMapper.writeValueAsString(result);
            String html = renderer.render(result);
            execution.setResultJson(resultJson);
            execution.setHtmlContent(html);

            DingTalkWebhookClient.PushResult push;
            if (!result.isBalanceCheckOk()) {
                push = new DingTalkWebhookClient.PushResult(ReportExecution.PUSH_SKIPPED,
                        "合计勾稽未通过（差额 " + result.getBalanceCheckDiff() + " 元），拒绝推送");
            } else {
                push = dingTalkClient.sendMarkdown(config.getReportTitle(), buildMarkdown(result));
            }
            execution.setPushStatus(push.status());
            execution.setPushDetail(push.detail());
            execution.setStatus(ReportExecution.STATUS_SUCCEEDED);
            execution.setDurationMs(System.currentTimeMillis() - start);
            executionMapper.updateById(execution);
            return execution;
        } catch (Exception e) {
            log.error("资金日报生成失败: date={}, owner={}", bizDate, ownerId, e);
            execution.setStatus(ReportExecution.STATUS_FAILED);
            execution.setErrorMessage(e.getMessage());
            execution.setPushStatus(ReportExecution.PUSH_SKIPPED);
            execution.setPushDetail("生成失败，未推送");
            execution.setDurationMs(System.currentTimeMillis() - start);
            executionMapper.updateById(execution);
            return execution;
        }
    }

    /** 查询单日执行记录（含 result_json 看板数据）。 */
    public ReportExecution getByDate(Long ownerId, LocalDate bizDate) {
        ReportDefinition definition = findDefinition(ownerId);
        if (definition == null) {
            return null;
        }
        return executionMapper.selectOne(new QueryWrapper<ReportExecution>()
                .eq("definition_id", definition.getId())
                .eq("biz_date", bizDate));
    }

    /** 区间执行记录列表（倒序）。 */
    public List<ReportExecution> listExecutions(Long ownerId, LocalDate from, LocalDate to) {
        ReportDefinition definition = findDefinition(ownerId);
        if (definition == null) {
            return List.of();
        }
        return executionMapper.selectList(new QueryWrapper<ReportExecution>()
                .eq("definition_id", definition.getId())
                .ge("biz_date", from)
                .le("biz_date", to)
                .orderByDesc("biz_date"));
    }

    FundDailyResult scanUserFiles(Long ownerId, LocalDate bizDate, FundReportConfig config) throws IOException {
        Path dir = reportRoot.resolve(String.valueOf(ownerId));
        List<Path> files = new ArrayList<>();
        if (Files.exists(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        return scanner.scan(files, bizDate, config);
    }

    ReportDefinition getOrCreateDefinition(Long ownerId) {
        ReportDefinition existing = findDefinition(ownerId);
        if (existing != null) {
            return existing;
        }
        ReportDefinition definition = new ReportDefinition();
        definition.setOwnerUserId(ownerId);
        definition.setName(REPORT_NAME);
        definition.setReportType(REPORT_TYPE);
        definition.setPipelineJson("{}");
        definition.setEnabled(true);
        definitionMapper.insert(definition);
        return definition;
    }

    private ReportDefinition findDefinition(Long ownerId) {
        return definitionMapper.selectOne(new QueryWrapper<ReportDefinition>()
                .eq("owner_user_id", ownerId)
                .eq("name", REPORT_NAME)
                .last("LIMIT 1"));
    }

    private ReportExecution getOrCreateExecution(Long definitionId, LocalDate bizDate) {
        ReportExecution existing = executionMapper.selectOne(new QueryWrapper<ReportExecution>()
                .eq("definition_id", definitionId)
                .eq("biz_date", bizDate));
        if (existing != null) {
            return existing;
        }
        ReportExecution execution = new ReportExecution();
        execution.setDefinitionId(definitionId);
        execution.setBizDate(bizDate);
        execution.setStatus(ReportExecution.STATUS_RUNNING);
        executionMapper.insert(execution);
        return execution;
    }

    /** pipeline_json → 业务规则配置（缺省字段回退默认值）。 */
    FundReportConfig loadConfig(ReportDefinition definition) {
        String json = definition.getPipelineJson();
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return defaultConfig();
        }
        try {
            return objectMapper.readerForUpdating(defaultConfig()).readValue(json);
        } catch (IOException e) {
            log.warn("报表配置解析失败，使用默认配置: {}", e.getMessage());
            return defaultConfig();
        }
    }

    private FundReportConfig defaultConfig() {
        return new FundReportConfig();
    }

    /** 钉钉 markdown 推送文本：顶层卡片 + 公司分组小计 + 勾稽/警告。 */
    String buildMarkdown(FundDailyResult result) {
        StringBuilder md = new StringBuilder();
        md.append("### ").append(result.getReportTitle()).append(" · ")
                .append(result.getReportDate()).append("\n\n");
        md.append("**上日余额** ").append(wan(result.getTotalPrev()))
                .append(" ｜ **本日收入** ").append(wan(result.getTotalIncome()))
                .append(" ｜ **本日支出** ").append(wan(result.getTotalPayout()))
                .append(" ｜ **本日结余** ").append(wan(result.getTotalBalance())).append("（").append(result.getDisplayUnit()).append("）\n\n");
        for (FundGroupResult group : result.getGroups()) {
            md.append("- ").append(group.getCompany()).append("：结余 ").append(wan(group.getBalance()))
                    .append("，收 ").append(wan(group.getIncome()))
                    .append("，支 ").append(wan(group.getPayout())).append("\n");
        }
        if (!result.isBalanceCheckOk()) {
            md.append("\n⚠ 合计勾稽差额 ").append(result.getBalanceCheckDiff()).append(" 元\n");
        }
        for (String warning : result.getWarnings()) {
            md.append("\n- ").append(warning);
        }
        md.append("\n\n详细看板请登录 argus-loom 查看。");
        return md.toString();
    }

    private String wan(BigDecimal value) {
        return value.divide(new BigDecimal("10000"), 2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
