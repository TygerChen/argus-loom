package com.argus.rag.assistant.agent;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.mapper.ExcelDocumentMapper;
import com.argus.rag.excel.pipeline.celldata.CellDataTable;
import com.argus.rag.excel.service.ExcelSheetService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务进度更新写工具（第十一步）——第一个真实写工具，也是确认门的第一个使用者。
 *
 * <p><b>写语义（本类与普通读工具的本质区别）</b>：{@code @Tool} 方法体里
 * <b>没有任何写执行代码</b>。模型调用它 = 运行器完成"定位→校验→登记提案→返回回执"
 * 四步，改动参数以确定性快照存入 {@link PendingProposalStore}；用户在界面上点确认后，
 * 确认 API（{@code AssistantProposalService}）按快照原样执行。模型从头到尾
 * 摸不到写执行路径——"大模型提建议，运行器管执行"在最敏感的写路径上成立。</p>
 *
 * <p><b>身份锚定</b>：userId 从 ToolContext（服务端会话状态）读取，与 KB 工具同一
 * 模式——模型/对话内容无法伪造身份。提案时的权限检查是<b>轻校验</b>（仅创建者，
 * 工具线程无 SecurityContext 拿不到完整角色链）；确认 API 在 HTTP 线程以完整
 * {@code requireEdit} 链终审——两道门，缺一不执行。</p>
 *
 * <p><b>失败语义</b>：定位失败/歧义/权限不足 → 返回结构化回执文本（模型可据此
 * 与用户澄清），不抛异常——与 KB 工具 INSUFFICIENT 模式一致。</p>
 */
@Component
public class AssistantTaskProgressWriteTool {

    private static final Logger log = LoggerFactory.getLogger(AssistantTaskProgressWriteTool.class);

    /** 任务进度表默认 5 列的定位列名（与 TaskProgressScanner 同口径）。 */
    static final String COL_TASK_NAME = "任务名称";
    static final String COL_STATUS = "状态";
    static final String COL_NOTE = "备注";

    /** 四态标准值（提案快照统一归一到这四态，与日报口径一致）。 */
    private static final List<String> CANONICAL_STATUSES =
            List.of("已完成", "进行中", "阻塞", "未开始");

    private final ExcelDocumentMapper documentMapper;
    private final ExcelSheetService excelSheetService;
    private final PendingProposalStore proposalStore;

    public AssistantTaskProgressWriteTool(ExcelDocumentMapper documentMapper,
                                          ExcelSheetService excelSheetService,
                                          PendingProposalStore proposalStore) {
        this.documentMapper = documentMapper;
        this.excelSheetService = excelSheetService;
        this.proposalStore = proposalStore;
    }

    @Tool(description = "更新任务进度表中的任务状态（写操作，需用户在界面上确认后才会执行）。" +
            "当用户要求把某任务标记为完成/进行中/阻塞/未开始，或修改任务备注时使用。" +
            "调用后系统会生成待确认提案，请向用户转述提案内容并提醒其点击确认")
    public String updateTaskProgress(
            @ToolParam(description = "任务名称（按任务进度表「任务名称」列匹配）") String taskName,
            @ToolParam(description = "新状态：已完成/进行中/阻塞/未开始") String newStatus,
            @ToolParam(required = false, description = "可选：更新备注列内容") String note,
            ToolContext toolContext
    ) {
        // 第六步门禁：在册 + 预算 + 写提案模式校验（循环内携带幂等键会被硬拒）
        ToolExecutionGuard.check(toolContext, "updateTaskProgress");
        Long userId = readUserId(toolContext);
        Long sessionId = readSessionId(toolContext);
        String name = taskName == null ? "" : taskName.trim();
        if (name.isEmpty()) {
            return "【提案未生成】任务名称不能为空，请向用户确认要更新哪个任务。";
        }
        String status = normalizeWriteStatus(newStatus);
        if (status == null) {
            return "【提案未生成】无法识别目标状态「" + newStatus + "」，请向用户确认是" +
                    "已完成/进行中/阻塞/未开始 中的哪一种。";
        }

        // ① 定位目标文档（轻权限校验：仅创建者；确认 API 以完整 requireEdit 链终审）
        ExcelDocument document = resolveTaskDocument(userId);
        if (document == null) {
            return "【提案未生成】未找到你创建的任务进度表文档（标题含「任务进度」）。" +
                    "请确认文档是否存在，或先在 Excel 协作模块创建。";
        }
        if (!String.valueOf(document.getCreatorId()).equals(String.valueOf(userId))) {
            log.warn("task.write: 非创建者提案被拒 user={} doc={}", userId, document.getId());
            return "【提案未生成】你不是该任务表的创建者，暂不支持通过助手修改他人文档。";
        }

        // ② 定位目标行与状态列
        TaskRowTarget target = locateTaskRow(document.getId(), name);
        if (target.error() != null) {
            return "【提案未生成】" + target.error();
        }

        // ③ 组装确定性改动快照 + 登记提案
        List<Map<String, Object>> updates = new ArrayList<>();
        updates.add(Map.of("sheetId", target.sheetId(), "r", target.row(),
                "c", target.statusCol(), "v", status));
        if (note != null && !note.isBlank()) {
            updates.add(Map.of("sheetId", target.sheetId(), "r", target.row(),
                    "c", target.noteCol(), "v", note.trim()));
        }
        String summary = "将任务「" + name + "」状态改为「" + status + "」"
                + (note != null && !note.isBlank() ? "，备注更新为「" + note.trim() + "」" : "")
                + "（文档：" + document.getName() + "）";
        PendingProposalStore.PendingProposal proposal;
        try {
            proposal = proposalStore.register(userId, sessionId, "updateTaskProgress",
                    summary, document.getId(), document.getName(), updates);
        } catch (IllegalStateException full) {
            return "【提案未生成】系统待确认提案已达上限，请稍后再试。";
        }

        // ④ 交给响应装配（前端据此渲染确认卡片）
        AssistantProposalHolder holder = readHolder(toolContext);
        if (holder != null) {
            holder.record(proposal);
        }

        log.info("task.write: proposal registered {} user={} doc={} row={} -> {}",
                proposal.proposalId(), userId, document.getId(), target.row(), status);
        return "【待确认写操作】提案号 " + proposal.proposalId() + "：" + summary +
                "。请向用户转述上述改动，并提醒用户点击确认按钮执行；确认前不会改动任何数据。";
    }

    // ── 定位逻辑（包可见纯函数 + Spring 装配两段，便于单测） ──

    /** 任务行定位结果（row=-1 表示未命中；error 非空表示定位失败原因）。 */
    record TaskRowTarget(Long sheetId, int row, int statusCol, int noteCol, String error) {
    }

    /**
     * 在文档第一个 Sheet 里定位任务行：读表头找「任务名称/状态/备注」列，
     * 再按任务名称精确匹配行。重名/未命中都以 error 回执（不瞎猜）。
     */
    TaskRowTarget locateTaskRow(Long documentId, String taskName) {
        List<ExcelSheet> sheets = excelSheetService.listSheetsByDocumentId(documentId);
        if (sheets == null || sheets.isEmpty()) {
            return new TaskRowTarget(null, -1, -1, -1, "文档下没有任何工作表。");
        }
        Long sheetId = sheets.get(0).getId();
        List<ExcelSheetChunk> chunks = excelSheetService.listChunksBySheetId(sheetId);
        CellDataTable table = CellDataTable.fromChunks(chunks);

        // 表头 → 列索引
        Map<String, Integer> headerCols = new LinkedHashMap<>();
        for (Map.Entry<Integer, Object> cell : table.rowAt(0).entrySet()) {
            String header = CellDataTable.valueAsText(cell.getValue());
            if (header != null && !header.isBlank()) {
                headerCols.putIfAbsent(header.trim(), cell.getKey());
            }
        }
        Integer nameCol = headerCols.get(COL_TASK_NAME);
        Integer statusCol = headerCols.get(COL_STATUS);
        if (nameCol == null || statusCol == null) {
            return new TaskRowTarget(sheetId, -1, -1, -1,
                    "任务表缺少「任务名称」或「状态」列（默认 5 列口径：任务名称/负责人/状态/截止日期/备注）。");
        }
        Integer noteCol = headerCols.get(COL_NOTE) == null ? -1 : headerCols.get(COL_NOTE);

        List<Integer> matchedRows = new ArrayList<>();
        for (Integer rowIndex : table.rowIndexes()) {
            if (rowIndex <= 0) {
                continue;
            }
            Object cell = table.rowAt(rowIndex).get(nameCol);
            String value = CellDataTable.valueAsText(cell);
            if (value != null && value.trim().equals(taskName)) {
                matchedRows.add(rowIndex);
            }
        }
        if (matchedRows.isEmpty()) {
            return new TaskRowTarget(sheetId, -1, nameCol, noteCol,
                    "任务表中未找到任务「" + taskName + "」，请确认任务名称。");
        }
        if (matchedRows.size() > 1) {
            return new TaskRowTarget(sheetId, -1, nameCol, noteCol,
                    "找到 " + matchedRows.size() + " 个同名任务「" + taskName + "」，请向用户确认具体是哪一个。");
        }
        return new TaskRowTarget(sheetId, matchedRows.get(0), statusCol, noteCol, null);
    }

    /**
     * 写入用状态归一（比日报扫描更严：识别不了返回 null 让用户澄清，
     * 绝不悄悄归到"未开始"——写错状态是真实副作用）。
     */
    static String normalizeWriteStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (CANONICAL_STATUSES.contains(text)) {
            return text;
        }
        if (text.contains("完成") || text.equalsIgnoreCase("done")) {
            return "已完成";
        }
        if (text.contains("阻塞") || text.contains("阻碍") || text.equalsIgnoreCase("blocked")) {
            return "阻塞";
        }
        if (text.contains("进行") || text.contains("在做") || text.contains("处理中")) {
            return "进行中";
        }
        if (text.contains("未开始") || text.contains("待开始") || text.contains("还没开始")) {
            return "未开始";
        }
        return null;
    }

    /** 按标题解析任务进度文档（标题含「任务进度」优先，其次含「任务」；多候选取精确匹配）。 */
    private ExcelDocument resolveTaskDocument(Long userId) {
        QueryWrapper<ExcelDocument> qw = new QueryWrapper<>();
        qw.eq("status", 1).eq("finance_required", false)
                .and(w -> w.like("name", "任务进度").or().likeRight("name", "任务"))
                .orderByDesc("update_time");
        List<ExcelDocument> candidates = documentMapper.selectList(qw);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // 同名多候选：交给确认 API 的完整权限链兜底；这里取更新时间最近的一份
        return candidates.get(0);
    }

    private Long readUserId(ToolContext toolContext) {
        Object userId = toolContext == null || toolContext.getContext() == null
                ? null : toolContext.getContext().get(AssistantKnowledgeBaseTool.USER_ID_CONTEXT_KEY);
        if (userId instanceof Long id && id > 0) {
            return id;
        }
        if (userId instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        throw new BusinessException("工具缺少用户身份上下文，无法定位任务表归属");
    }

    private Long readSessionId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return 0L;
        }
        Object sessionId = toolContext.getContext().get("sessionId");
        return sessionId instanceof Long id ? id : 0L;
    }

    private AssistantProposalHolder readHolder(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object holder = toolContext.getContext().get("proposalHolder");
        return holder instanceof AssistantProposalHolder proposalHolder ? proposalHolder : null;
    }
}
