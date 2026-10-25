package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.model.dto.FinanceRecordUpdateRequest;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.FinanceImportBatch;
import com.argus.rag.excel.entity.FinanceLedgerRecord;
import com.argus.rag.excel.finance.FinanceWorkbookExtractor;
import com.argus.rag.excel.mapper.FinanceImportBatchMapper;
import com.argus.rag.excel.mapper.FinanceLedgerRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.argus.rag.common.config.ArgusRagProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 财务标准流水导入服务 —— 把 Excel 流水账变成可查询、可版本化、可审计的台账。
 *
 * <p><b>一句话讲清它干什么</b>：用户上传一份财务 Excel（如"北京 2026 流水账"），
 * 本服务把原文件存盘 → 按 01 篇的分块链路生成在线可看的 Excel 文档 →
 * 从表格里抽出结构化流水行写入 {@code finance_ledger_record}（台账表），
 * 并在 {@code finance_import_batch}（批次表）登记"这次导入"。</p>
 *
 * <p><b>四条业务铁律</b>（每条都对应一个面试考点）：</p>
 * <ol>
 *   <li><b>幂等</b>：文件算 SHA-256 指纹，同一文件拒绝导入两次——
 *       防止"重复记账"这种资金事故（区别于知识库上传的静默秒传，见 01 篇 Q2）；</li>
 *   <li><b>关账守卫</b>：业务日期所在月份已关账 → 拒绝导入/修改（fail-closed，
 *       见 07 篇）；</li>
 *   <li><b>版本化</b>：同一账本（bookKey）反复导入会产生 batchVersion 1、2、3…，
 *       只有一个是"当前版本"，旧版本不删——随时可整本回退（activateBatch）；</li>
 *   <li><b>并发安全</b>：逐条修改流水时用"行级悲观锁 + 指纹乐观锁"双层防护，
 *       两个人同时改一行不会互相覆盖（batchUpdateRecords）。</li>
 * </ol>
 *
 * <p><b>为什么查统计用 JdbcTemplate 手写 SQL，而不是 MyBatis-Plus？</b>
 * 概览/账户汇总/每日趋势是多表 JOIN + 聚合的分析型查询，字段组合复杂，
 * 手写 SQL（参数全部用 ? 占位符，杜绝注入）反而比把 QueryWrapper 拼成嵌套
 * 地狱更直白。单表 CRUD 才是 MyBatis-Plus 的主场。</p>
 */
@Service
public class FinanceImportService {

    private static final Logger log = LoggerFactory.getLogger(FinanceImportService.class);

    private final FinanceWorkbookExtractor extractor;
    private final ExcelDocumentService documentService;
    private final ExcelParserService parserService;
    private final FinanceImportBatchMapper batchMapper;
    private final FinanceLedgerRecordMapper ledgerMapper;
    private final FinancePeriodService periodService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CurrentUserService currentUserService;

    /** 上传根目录：生产由共享配置 bean 绑定（构造时快照），测试可反射重定向到临时目录。 */
    private String uploadPath;

    /** 生产构造：注入共享配置 bean（上传路径收口于 argus.rag.excel.upload-path）与期间服务。 */
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceImportService(FinanceWorkbookExtractor extractor,
                                ExcelDocumentService documentService,
                                ExcelParserService parserService,
                                FinanceImportBatchMapper batchMapper,
                                FinanceLedgerRecordMapper ledgerMapper,
                                JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper,
                                CurrentUserService currentUserService,
                                ArgusRagProperties argusProperties,
                                FinancePeriodService periodService) {
        this.extractor = extractor;
        this.documentService = documentService;
        this.parserService = parserService;
        this.batchMapper = batchMapper;
        this.ledgerMapper = ledgerMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.currentUserService = currentUserService;
        this.uploadPath = argusProperties.getUploadPath();
        this.periodService = periodService;
    }

    /**
     * 导入主流程 —— 整个方法包在一个事务里（rollbackFor = 任何异常都回滚）：
     * 中途任何一步失败，"文件、文档、批次、流水行"全部回退，不会留半截数据。
     *
     * <p>执行顺序（每一步都标了注释，对着编号读）：</p>
     * <ol>
     *   <li>存盘 + 算指纹 + 幂等检查（同文件拒绝）；</li>
     *   <li>从工作簿抽取结构化流水（extractor 干重活）；</li>
     *   <li>关账守卫：文件里最早的业务日期所在月已关账 → 拒绝；</li>
     *   <li>创建 ExcelDocument（这样导入的文件也走 01 篇的分块链路，能在线看）；</li>
     *   <li>分块解析入库（parseAndSave，05 篇的解析逻辑）；</li>
     *   <li>批次版本管理：同账本旧批次全部"退位"，本批次以 nextVersion 成为当前版本；</li>
     *   <li>流水行批量写入（insertRecords，512 条一批）。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public FinanceImportBatch importFile(MultipartFile file,
                                         String bookKey,
                                         String regionName,
                                         String currencyCode) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }
        String originalName = file.getOriginalFilename();
        Path saved = saveFile(file);
        // 文件幂等（任务 10）：同 SHA-256 文件已导入过就拒绝，避免重复流水。
        String fileHash = sha256Hex(saved);
        assertFileNotImported(fileHash);

        FinanceWorkbookExtractor.ExtractionResult extraction = extractAt(saved, currencyCode);
        if (extraction.records.isEmpty()) {
            throw new BusinessException("未从工作簿读取到可导入的财务流水");
        }
        // 期间关闭守卫（任务 10）：文件内最早业务日期所在月已关闭则拒绝导入。
        periodService.assertPeriodOpen(extractionDateGuard(extraction));

        ExcelDocument document = new ExcelDocument();
        document.setName(originalName);
        document.setFilePath(saved.toString());
        document.setFileSize(file.getSize());
        document.setCreatorId(String.valueOf(currentUserService.getRequiredCurrentUser().userId()));
        document.setFinanceRequired(true);
        documentService.create(document);

        List<ExcelSheet> sheets;
        try (InputStream is = Files.newInputStream(saved)) {
            sheets = parserService.parseAndSave(is, document);
        }
        documentService.updateSheetMeta(
                document.getId(),
                sheets.size(),
                objectMapper.writeValueAsString(
                        sheets.stream().map(ExcelSheet::getSheetName).toList()));

        FinanceImportBatch batch = buildBatch(document, originalName, bookKey, regionName, extraction);
        batch.setFileHash(fileHash);
        int nextVersion = nextBatchVersion(batch.getBookKey());
        if (nextVersion > 1) {
            batchMapper.update(null, new UpdateWrapper<FinanceImportBatch>()
                    .eq("book_key", batch.getBookKey())
                    .set("is_current", 0));
        }
        batch.setBatchVersion(nextVersion);
        batch.setIsCurrent(1);
        batchMapper.insert(batch);
        insertRecords(batch, extraction.records);
        log.info("财务导入完成: documentId={}, batchId={}, rows={}",
                document.getId(), batch.getId(), extraction.records.size());
        return batch;
    }

    /** 分页列出导入批次：当前版本排最前，其后按版本号新→旧。 */
    public Page<FinanceImportBatch> pageBatches(int pageNum, int pageSize) {
        Page<FinanceImportBatch> page = new Page<>(Math.max(pageNum, 1), Math.min(pageSize, 200));
        QueryWrapper<FinanceImportBatch> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("is_current").orderByDesc("batch_version").orderByDesc("id");
        return batchMapper.selectPage(page, wrapper);
    }

    /** 列出同一账本的全部历史版本（版本回退界面的数据源）。 */
    public List<FinanceImportBatch> listBatchVersions(String bookKey) {
        if (!StringUtils.hasText(bookKey)) {
            return List.of();
        }
        QueryWrapper<FinanceImportBatch> wrapper = new QueryWrapper<>();
        wrapper.eq("book_key", bookKey)
                .orderByDesc("batch_version")
                .orderByDesc("id");
        return batchMapper.selectList(wrapper);
    }

    /** 按批次分页看流水行，顺序与 Excel 原始行序一致（sheet 内行号升序）。 */
    public Page<FinanceLedgerRecord> pageRecords(Long batchId, int pageNum, int pageSize) {
        Page<FinanceLedgerRecord> page = new Page<>(Math.max(pageNum, 1), Math.min(pageSize, 500));
        QueryWrapper<FinanceLedgerRecord> wrapper = new QueryWrapper<>();
        wrapper.eq("batch_id", batchId)
                .orderByAsc("sheet_index", "excel_row");
        return ledgerMapper.selectPage(page, wrapper);
    }

    /**
     * 多条件筛选流水行（台账查询页的主力接口）。
     *
     * <p>所有条件都是"可选拼装"：传了就加 WHERE，没传就不限制。
     * 三个容易被面试官追问的细节：</p>
     * <ul>
     *   <li><b>includeAll=false 时默认只看"当前版本"批次</b>——不然新旧版本
     *       同一笔流水会出现两遍。实现方式：不传批次/文档条件时，
     *       用 inSql 子查询圈定 is_current=1 的批次；</li>
     *   <li><b>regionName 是批次表的属性</b>，所以要先查出该区域的所有批次 ID，
     *       再用 IN 条件过滤流水表（跨表条件的两段式写法）；</li>
     *   <li><b>keyword 是跨四列的模糊搜索</b>（摘要/往来单位/部门/费用名称），
     *       括号分组保证 OR 不吃掉外面的 AND 条件——SQL 布尔逻辑的经典坑。</li>
     * </ul>
     */
    public Page<FinanceLedgerRecord> pageFilteredRecords(Long batchId,
                                                         Long documentId,
                                                         String regionName,
                                                         String sheetName,
                                                         String currencyCode,
                                                         String recordType,
                                                         String keyword,
                                                         LocalDate fromDate,
                                                         LocalDate toDate,
                                                         boolean includeAll,
                                                         int pageNum,
                                                         int pageSize) {
        Page<FinanceLedgerRecord> page = new Page<>(Math.max(pageNum, 1), Math.min(pageSize, 500));
        QueryWrapper<FinanceLedgerRecord> wrapper = new QueryWrapper<>();
        if (batchId != null) {
            wrapper.eq("batch_id", batchId);
        }
        if (documentId != null) {
            wrapper.eq("document_id", documentId);
        }
        if (StringUtils.hasText(regionName)) {
            QueryWrapper<FinanceImportBatch> regionQuery = new QueryWrapper<FinanceImportBatch>()
                    .select("id")
                    .eq("region_name", regionName);
            if (!includeAll) {
                regionQuery.eq("is_current", 1);
            }
            List<Long> batchIds = batchMapper.selectList(regionQuery)
                    .stream()
                    .map(FinanceImportBatch::getId)
                    .toList();
            if (batchIds.isEmpty()) {
                return page;
            }
            wrapper.in("batch_id", batchIds);
        }
        if (!includeAll && batchId == null && documentId == null) {
            wrapper.inSql("batch_id", "SELECT id FROM finance_import_batch WHERE is_current = 1");
        }
        if (StringUtils.hasText(sheetName)) {
            wrapper.eq("sheet_name", sheetName);
        }
        if (StringUtils.hasText(currencyCode)) {
            wrapper.eq("currency_code", currencyCode);
        }
        if (StringUtils.hasText(recordType)) {
            wrapper.eq("record_type", recordType);
        }
        if (fromDate != null) {
            wrapper.ge("business_date", fromDate);
        }
        if (toDate != null) {
            wrapper.le("business_date", toDate);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like("summary", keyword)
                    .or().like("counterparty", keyword)
                    .or().like("department", keyword)
                    .or().like("expense_name", keyword));
        }
        wrapper.orderByAsc("sheet_index").orderByAsc("excel_row");
        return ledgerMapper.selectPage(page, wrapper);
    }

    /**
     * 财务总览卡片（页面顶部那一排数字）：记录数、批次数、账户数、
     * 日期范围、借/贷/收入/支出总额、#REF! 坏公式警告数，以及按币种分组的小计。
     *
     * <p>性能思路：十来个聚合数一次性算完，共用同一个 FROM/WHERE
     * （{@link #ledgerFromClause} 拼出来），避免前端发十个请求。</p>
     */
    public Map<String, Object> financeOverview(String regionName,
                                               Long batchId,
                                               String currencyCode,
                                               LocalDate fromDate,
                                               LocalDate toDate,
                                               boolean includeAll) {
        List<Object> params = new ArrayList<>();
        String fromSql = ledgerFromClause(regionName, batchId, currencyCode, fromDate, toDate, params, includeAll);
        Object[] args = params.toArray();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordCount", jdbcTemplate.queryForObject("SELECT COUNT(*) " + fromSql, Long.class, args));
        data.put("batchCount", jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT r.batch_id) " + fromSql, Long.class, args));
        data.put("accountCount", jdbcTemplate.queryForObject(
                // PostgreSQL 不支持 COUNT(DISTINCT a, b, c)，用行值表达式统计唯一账户口径
                "SELECT COUNT(DISTINCT (r.batch_id, r.sheet_name, r.currency_code)) " + fromSql,
                Long.class, args));
        data.put("dateMin", jdbcTemplate.queryForObject(
                "SELECT MIN(r.business_date) " + fromSql, LocalDate.class, args));
        data.put("dateMax", jdbcTemplate.queryForObject(
                "SELECT MAX(r.business_date) " + fromSql, LocalDate.class, args));
        data.put("debitTotal", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(r.debit), 0) " + fromSql, BigDecimal.class, args));
        data.put("creditTotal", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(r.credit), 0) " + fromSql, BigDecimal.class, args));
        data.put("incomeTotal", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(r.income), 0) " + fromSql, BigDecimal.class, args));
        data.put("expenseTotal", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(r.expense), 0) " + fromSql, BigDecimal.class, args));
        data.put("warningCount", jdbcTemplate.queryForObject(
                "SELECT COUNT(CASE WHEN r.formula_text LIKE '%#REF!%' OR r.warnings LIKE '%#REF!%' THEN 1 END) "
                        + fromSql, Long.class, args));

        List<Map<String, Object>> currencyTotals = jdbcTemplate.query(
                "SELECT r.currency_code AS currencyCode, "
                        + "COUNT(*) AS recordCount, "
                        + "COALESCE(SUM(r.debit), 0) AS debitTotal, "
                        + "COALESCE(SUM(r.credit), 0) AS creditTotal, "
                        + "COALESCE(SUM(r.income), 0) AS incomeTotal, "
                        + "COALESCE(SUM(r.expense), 0) AS expenseTotal "
                        + fromSql + " GROUP BY r.currency_code ORDER BY r.currency_code",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("currencyCode", rs.getString("currencyCode"));
                    item.put("recordCount", rs.getLong("recordCount"));
                    item.put("debitTotal", rs.getBigDecimal("debitTotal"));
                    item.put("creditTotal", rs.getBigDecimal("creditTotal"));
                    item.put("incomeTotal", rs.getBigDecimal("incomeTotal"));
                    item.put("expenseTotal", rs.getBigDecimal("expenseTotal"));
                    return item;
                },
                args);
        data.put("currencyTotals", currencyTotals);
        return data;
    }

    /** 账户维度汇总：按“批次 + Sheet + 币种”分组，附期初/期末余额。 */
    public List<Map<String, Object>> accountSummaries(String regionName,
                                                      Long batchId,
                                                      String currencyCode,
                                                      boolean includeAll) {
        List<Object> params = new ArrayList<>();
        String fromSql = ledgerFromClause(regionName, batchId, currencyCode, null, null, params, includeAll)
                + " ";
        String sql = """
                SELECT r.batch_id AS batchId, r.document_id AS documentId,
                       b.region_name AS regionName, b.file_name AS fileName,
                       b.book_key AS bookKey, b.batch_version AS batchVersion,
                       r.sheet_name AS sheetName, r.currency_code AS currencyCode,
                       MIN(r.business_date) AS firstDate, MAX(r.business_date) AS lastDate,
                       COUNT(*) AS recordCount,
                       SUM(CASE WHEN r.record_type = 'OPENING' THEN 1 ELSE 0 END) AS openingCount,
                       COALESCE(SUM(r.debit), 0) AS debitTotal,
                       COALESCE(SUM(r.credit), 0) AS creditTotal,
                       COALESCE(SUM(r.income), 0) AS incomeTotal,
                       COALESCE(SUM(r.expense), 0) AS expenseTotal,
                       (SELECT r2.balance FROM finance_ledger_record r2
                         WHERE r2.batch_id = r.batch_id AND r2.sheet_name = r.sheet_name
                           AND r2.currency_code = r.currency_code
                         ORDER BY r2.business_date ASC, r2.excel_row ASC LIMIT 1) AS openingBalance,
                       (SELECT r3.balance FROM finance_ledger_record r3
                         WHERE r3.batch_id = r.batch_id AND r3.sheet_name = r.sheet_name
                           AND r3.currency_code = r.currency_code
                         ORDER BY r3.business_date DESC, r3.excel_row DESC LIMIT 1) AS endingBalance
                """ + fromSql + """
                GROUP BY r.batch_id, r.document_id, b.region_name, b.file_name,
                         b.book_key, b.batch_version, r.sheet_name, r.currency_code
                ORDER BY b.region_name, r.sheet_name, r.currency_code
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchId", rs.getLong("batchId"));
            row.put("documentId", rs.getLong("documentId"));
            row.put("regionName", rs.getString("regionName"));
            row.put("fileName", rs.getString("fileName"));
            row.put("bookKey", rs.getString("bookKey"));
            row.put("batchVersion", rs.getInt("batchVersion"));
            row.put("sheetName", rs.getString("sheetName"));
            row.put("currencyCode", rs.getString("currencyCode"));
            row.put("firstDate", rs.getObject("firstDate", LocalDate.class));
            row.put("lastDate", rs.getObject("lastDate", LocalDate.class));
            row.put("recordCount", rs.getLong("recordCount"));
            row.put("openingCount", rs.getLong("openingCount"));
            row.put("debitTotal", rs.getBigDecimal("debitTotal"));
            row.put("creditTotal", rs.getBigDecimal("creditTotal"));
            row.put("incomeTotal", rs.getBigDecimal("incomeTotal"));
            row.put("expenseTotal", rs.getBigDecimal("expenseTotal"));
            row.put("openingBalance", rs.getBigDecimal("openingBalance"));
            row.put("endingBalance", rs.getBigDecimal("endingBalance"));
            return row;
        }, params.toArray());
    }

    /** 每日发生额汇总，最近最多 366 天，适合画趋势图。 */
    public List<Map<String, Object>> dailySummaries(String regionName,
                                                     Long batchId,
                                                     String currencyCode,
                                                     LocalDate fromDate,
                                                     LocalDate toDate,
                                                     boolean includeAll) {
        List<Object> params = new ArrayList<>();
        String fromSql = ledgerFromClause(regionName, batchId, currencyCode, fromDate, toDate, params, includeAll);
        String sql = "SELECT r.business_date AS businessDate, "
                + "COUNT(*) AS recordCount, "
                + "COALESCE(SUM(r.debit), 0) AS debitTotal, "
                + "COALESCE(SUM(r.credit), 0) AS creditTotal, "
                + "COALESCE(SUM(r.income), 0) AS incomeTotal, "
                + "COALESCE(SUM(r.expense), 0) AS expenseTotal "
                + fromSql
                + " GROUP BY r.business_date ORDER BY r.business_date DESC LIMIT 366";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("businessDate", rs.getObject("businessDate", LocalDate.class));
            row.put("recordCount", rs.getLong("recordCount"));
            row.put("debitTotal", rs.getBigDecimal("debitTotal"));
            row.put("creditTotal", rs.getBigDecimal("creditTotal"));
            row.put("incomeTotal", rs.getBigDecimal("incomeTotal"));
            row.put("expenseTotal", rs.getBigDecimal("expenseTotal"));
            return row;
        }, params.toArray());
    }

    /**
     * 批量修改流水行（台账编辑页保存按钮的后端）。
     *
     * <p><b>并发防护是双层锁，这是本方法最值得学的地方</b>：</p>
     * <ol>
     *   <li><b>行级悲观锁</b>（selectByIdForUpdate，即 SELECT ... FOR UPDATE）：
     *       把这一行"捏在手里"，其他事务想改同一行必须等——
     *       让"读指纹 → 比对 → 更新"三步变成原子操作，读的时候没人能插进来改；</li>
     *   <li><b>指纹乐观锁</b>：前端提交时带上"我编辑时看到的指纹"
     *       （expectedFingerprint，由 17 个业务字段拼串算出，见
     *       {@link #recordFingerprint}）。和当前指纹对不上 = 有人先改过了
     *       → 抛 CONCURRENT_MODIFIED 让用户刷新决策。</li>
     * </ol>
     *
     * <p>为什么两层都要？悲观锁防的是"同时写"（物理防护），
     * 乐观锁防的是"你不是基于最新数据在改"（语义防护）——
     * 用户 A 读完后用户 B 改了又提交，A 再提交时悲观锁早已释放，
     * 只有指纹比对能拦住这种"过期写"。改动前还查一次关账状态，
     * 关账月份的记录一个字段都不许动。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateRecords(List<FinanceRecordUpdateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (FinanceRecordUpdateRequest request : requests) {
            if (request.id() == null) {
                throw new BusinessException("明细记录 ID 不能为空");
            }
            // 行级悲观锁读取，把 fingerprint 乐观锁的“读-比-写”变成原子，杜绝并发互相覆盖。
            FinanceLedgerRecord current = ledgerMapper.selectByIdForUpdate(request.id());
            if (current == null) {
                throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "明细记录 " + request.id() + " 不存在");
            }
            if (StringUtils.hasText(request.expectedFingerprint())
                    && !request.expectedFingerprint().equals(recordFingerprint(current))) {
                throw new BusinessException(ErrorCodes.CONCURRENT_MODIFIED,
                        "记录 " + request.id() + " 已被其他用户修改，请刷新后重试");
            }
            // 期间关闭守卫（任务 10）：目标记录业务日期所在月已关闭则拒绝修改。
            periodService.assertPeriodOpen(current.getBusinessDate());
            UpdateWrapper<FinanceLedgerRecord> wrapper = new UpdateWrapper<>();
            wrapper.eq("id", request.id())
                    .set("business_date", request.businessDate())
                    .set("day_label", request.dayLabel())
                    .set("summary", request.summary())
                    .set("counterparty", request.counterparty())
                    .set("department", request.department())
                    .set("category", request.category())
                    .set("expense_name", request.expenseName())
                    .set("debit", request.debit())
                    .set("credit", request.credit())
                    .set("balance", request.balance())
                    .set("prev_balance", request.prevBalance())
                    .set("income", request.income())
                    .set("expense", request.expense())
                    .set("fixed_purchase", request.fixedPurchase())
                    .set("fixed_redemption", request.fixedRedemption())
                    .set("fixed_balance", request.fixedBalance())
                    .set("currency_code", request.currencyCode())
                    .set("warnings", request.warnings());
            updated += ledgerMapper.update(null, wrapper);
        }
        return updated;
    }

    /**
     * 版本回退：把目标批次设为"当前版本"。
     *
     * <p>两步走且在同一事务里：先把同账本所有批次的 is_current 清零，
     * 再把目标批次置 1——保证任何时刻同账本至多一个当前版本。
     * 注意旧批次的流水行从未被删除，回退 = 换个视角看历史数据，
     * 而不是把数据搬来搬去。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public FinanceImportBatch activateBatch(Long batchId) {
        FinanceImportBatch target = batchMapper.selectById(batchId);
        if (target == null) {
            throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "导入批次不存在: " + batchId);
        }
        if (!StringUtils.hasText(target.getBookKey())) {
            throw new BusinessException("该批次没有账本标识，无法作为版本回滚目标");
        }
        batchMapper.update(null, new UpdateWrapper<FinanceImportBatch>()
                .eq("book_key", target.getBookKey())
                .set("is_current", 0));
        target.setIsCurrent(1);
        batchMapper.updateById(target);
        return target;
    }

    /**
     * 拼 FROM + WHERE 子句（overview / accountSummaries / dailySummaries 共用）。
     * 参数值一律走 ? 占位符（params 列表按顺序对应），从根上杜绝 SQL 注入；
     * 这比字符串拼接用户输入安全得多——值和数据永远不混在一起。
     */
    private String ledgerFromClause(String regionName,
                                    Long batchId,
                                    String currencyCode,
                                    LocalDate fromDate,
                                    LocalDate toDate,
                                    List<Object> params,
                                    boolean includeAll) {
        StringBuilder sql = new StringBuilder("FROM finance_ledger_record r ");
        sql.append("JOIN finance_import_batch b ON r.batch_id = b.id ");
        List<String> conditions = new ArrayList<>();
        if (StringUtils.hasText(regionName)) {
            conditions.add("b.region_name = ?");
            params.add(regionName);
        }
        if (batchId != null) {
            conditions.add("r.batch_id = ?");
            params.add(batchId);
        } else if (!includeAll) {
            conditions.add("b.is_current = 1");
        }
        if (StringUtils.hasText(currencyCode)) {
            conditions.add("r.currency_code = ?");
            params.add(currencyCode);
        }
        if (fromDate != null) {
            conditions.add("r.business_date >= ?");
            params.add(fromDate);
        }
        if (toDate != null) {
            conditions.add("r.business_date <= ?");
            params.add(toDate);
        }
        if (!conditions.isEmpty()) {
            sql.append("WHERE ").append(String.join(" AND ", conditions));
        }
        return sql.toString();
    }

    /** 同账本的下一个版本号：没有历史 = 1，否则最新版本 + 1。 */
    int nextBatchVersion(String bookKey) {
        if (!StringUtils.hasText(bookKey)) {
            return 1;
        }
        FinanceImportBatch latest = batchMapper.selectOne(
                new QueryWrapper<FinanceImportBatch>()
                        .select("batch_version")
                        .eq("book_key", bookKey)
                        .orderByDesc("batch_version")
                        .last("LIMIT 1"));
        return latest == null || latest.getBatchVersion() == null
                ? 1
                : latest.getBatchVersion() + 1;
    }

    /**
     * 行级指纹：把一行业务数据的 17 个字段按固定顺序拼串。
     * 用于乐观锁比对——"你编辑时看到的行"和"数据库现在的行"是否一致。
     * 任何字段变了指纹必变；用 | 做分隔符防止字段值粘连产生歧义。
     */
    static String recordFingerprint(FinanceLedgerRecord record) {
        return String.join("|",
                text(record.getDayLabel()),
                text(record.getSummary()),
                text(record.getCounterparty()),
                text(record.getDepartment()),
                text(record.getCategory()),
                text(record.getExpenseName()),
                record.getBusinessDate() == null ? "" : record.getBusinessDate().toString(),
                amount(record.getDebit()),
                amount(record.getCredit()),
                amount(record.getBalance()),
                amount(record.getPrevBalance()),
                amount(record.getIncome()),
                amount(record.getExpense()),
                amount(record.getFixedPurchase()),
                amount(record.getFixedRedemption()),
                amount(record.getFixedBalance()),
                text(record.getCurrencyCode()),
                text(record.getWarnings()));
    }

    static String text(String value) {
        return value == null ? "" : value;
    }

    static String amount(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private FinanceWorkbookExtractor.ExtractionResult extractAt(Path file, String currencyCode) throws Exception {
        try (InputStream is = Files.newInputStream(file);
             Workbook workbook = WorkbookFactory.create(is)) {
            return extractor.extract(workbook, currencyCode);
        }
    }

    /**
     * 流水行批量写入。为什么不用 MyBatis-Plus 的 insert 循环？
     * 一个批次可能上万行，逐条 insert = 上万次数据库往返；
     * JdbcTemplate.batchUpdate 每 512 行打包成一次网络往返（批处理），
     * 导入速度差出一个数量级。这是"批量写"的标准姿势。
     */
    private void insertRecords(FinanceImportBatch batch, List<FinanceLedgerRecord> records) {
        String sql = """
                INSERT INTO finance_ledger_record (
                    batch_id, document_id, sheet_index, sheet_name, excel_row,
                    record_type, business_date, day_label, summary, counterparty,
                    department, category, expense_name, debit, credit, balance,
                    fixed_purchase, fixed_redemption, fixed_balance, prev_balance,
                    income, expense, currency_code, raw_json, formula_text,
                    row_status, warnings
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.batchUpdate(sql, records, 512, (ps, record) -> {
            ps.setLong(1, batch.getId());
            ps.setLong(2, batch.getDocumentId());
            ps.setInt(3, record.getSheetIndex());
            ps.setString(4, record.getSheetName());
            ps.setInt(5, record.getExcelRow());
            ps.setString(6, record.getRecordType());
            setDate(ps, 7, record.getBusinessDate());
            ps.setString(8, record.getDayLabel());
            ps.setString(9, record.getSummary());
            ps.setString(10, record.getCounterparty());
            ps.setString(11, record.getDepartment());
            ps.setString(12, record.getCategory());
            ps.setString(13, record.getExpenseName());
            setAmount(ps, 14, record.getDebit());
            setAmount(ps, 15, record.getCredit());
            setAmount(ps, 16, record.getBalance());
            setAmount(ps, 17, record.getFixedPurchase());
            setAmount(ps, 18, record.getFixedRedemption());
            setAmount(ps, 19, record.getFixedBalance());
            setAmount(ps, 20, record.getPrevBalance());
            setAmount(ps, 21, record.getIncome());
            setAmount(ps, 22, record.getExpense());
            ps.setString(23, record.getCurrencyCode());
            ps.setString(24, record.getRawJson());
            ps.setString(25, record.getFormulaText());
            ps.setInt(26, record.getRowStatus());
            ps.setString(27, record.getWarnings());
        });
        log.info("批量写入财务流水 {} 条", records.size());
    }

    /**
     * 组装批次记录：把"这次导入"的所有元信息（来源文件、账本、区域、
     * Sheet/行数统计、是否跳过过 Sheet）登记成 finance_import_batch 的一行。
     * status：2 = 全部 Sheet 都导入成功；3 = 有 Sheet 被跳过（部分成功）。
     */
    FinanceImportBatch buildBatch(ExcelDocument document,
                                          String originalName,
                                          String bookKey,
                                          String regionName,
                                          FinanceWorkbookExtractor.ExtractionResult extraction) {
        FinanceImportBatch batch = new FinanceImportBatch();
        batch.setDocumentId(document.getId());
        batch.setBookKey(StringUtils.hasText(bookKey) ? bookKey : inferBookKey(originalName));
        batch.setRegionName(StringUtils.hasText(regionName) ? regionName : inferRegion(originalName));
        batch.setFileName(originalName);
        batch.setFilePath(document.getFilePath());
        batch.setFileSize(document.getFileSize());
        batch.setTotalSheets(extraction.importedSheets + extraction.skippedSheets.size());
        batch.setImportedSheets(extraction.importedSheets);
        batch.setTotalRows(extraction.records.size() + extraction.skippedRows);
        batch.setMappedRows(extraction.records.size());
        batch.setSkippedRows(extraction.skippedRows);
        batch.setStatus(extraction.skippedSheets.isEmpty() ? 2 : 3);
        batch.setErrorSummary(extraction.skippedSheets.isEmpty()
                ? null
                : "跳过 Sheet: " + String.join("; ", extraction.skippedSheets.keySet()));
        return batch;
    }

    /**
     * 原文件落盘。安全细节：随机 UUID 前缀 + 清洗文件名里的路径非法字符
     * （\ / : * ? " < > |）——原文件名永远不决定存储路径的形态，
     * 和 01 篇上传的"文件名消毒 + 随机改名"是同一套防线。
     */
    private Path saveFile(MultipartFile file) throws Exception {
        Path dir = Paths.get(uploadPath).toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        String originalName = file.getOriginalFilename() == null ? "finance.xlsx" : file.getOriginalFilename();
        String safeName = UUID.randomUUID() + "_" + originalName.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path path = dir.resolve(safeName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
        }
        return path;
    }

    /**
     * 文件幂等校验：同哈希文件已存在批次时抛 FINANCE_FILE_DUPLICATED。
     * 包内可见供测试直接验证。
     */
    void assertFileNotImported(String fileHash) {
        if (!StringUtils.hasText(fileHash)) {
            return;
        }
        Long count = batchMapper.selectCount(new QueryWrapper<FinanceImportBatch>()
                .eq("file_hash", fileHash));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCodes.FINANCE_FILE_DUPLICATED,
                    "该文件已导入过，请勿重复上传；如需重导请先删除旧批次或改用新文件");
        }
    }

    /** 计算文件 SHA-256（十六进制小写）。 */
    private static String sha256Hex(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** 期间守卫基准日期：文件内最早业务日期（无数据时用今天）。 */
    private static LocalDate extractionDateGuard(FinanceWorkbookExtractor.ExtractionResult extraction) {
        return extraction.records.stream()
                .map(FinanceLedgerRecord::getBusinessDate)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());
    }

    /** 从文件名推断账本标识，用于没有显式传 bookKey 的旧版导入。 */
    String inferBookKey(String fileName) {
        if (fileName != null && fileName.contains("北京")) {
            return "BJ-2026";
        }
        if (fileName != null && fileName.contains("唐山")) {
            return "TS-2026";
        }
        return "UNIT-2026";
    }

    /** 从文件名推断区域名称。 */
    String inferRegion(String fileName) {
        if (fileName != null && fileName.contains("北京")) {
            return "北京";
        }
        if (fileName != null && fileName.contains("唐山")) {
            return "唐山";
        }
        return "其他单位";
    }

    private static void setAmount(java.sql.PreparedStatement ps, int index, BigDecimal value) throws java.sql.SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    private static void setDate(java.sql.PreparedStatement ps, int index, LocalDate value) throws java.sql.SQLException {
        ps.setObject(index, value);
    }
}
