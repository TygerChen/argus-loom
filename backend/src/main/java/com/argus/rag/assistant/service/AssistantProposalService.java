package com.argus.rag.assistant.service;

import com.argus.rag.assistant.agent.PendingProposalStore;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.excel.audit.OperationLogService;
import com.argus.rag.excel.service.ExcelPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 写提案确认服务（第十一步）——确认门执行侧的运行器。
 *
 * <p><b>职责边界</b>：对话循环里的写工具只登记提案；本类是<b>唯一</b>把提案
 * 变成真实写动作的地方。执行链五步缺一不可：</p>
 * <ol>
 *   <li>提案状态机 CAS（REGISTERED→EXECUTED，owner 校验 + TTL + 并发双击只有一方成功）；</li>
 *   <li>完整权限终审 {@code requireEdit}（HTTP 线程有 SecurityContext，走全角色链）；</li>
 *   <li>按提案快照原样执行（不重新解析、不问模型）；</li>
 *   <li>走 {@code OperationLogService} 统一留痕（operation_log + 增量回写同一事务口径）；</li>
 *   <li>幂等键 = proposalId——重复确认被状态机拒绝，绝不二次写入。</li>
 * </ol>
 */
@Service
public class AssistantProposalService {

    private static final Logger log = LoggerFactory.getLogger(AssistantProposalService.class);

    private final PendingProposalStore proposalStore;
    private final ExcelPermissionService permissionService;
    private final OperationLogService operationLogService;

    public AssistantProposalService(PendingProposalStore proposalStore,
                                    ExcelPermissionService permissionService,
                                    OperationLogService operationLogService) {
        this.proposalStore = proposalStore;
        this.permissionService = permissionService;
        this.operationLogService = operationLogService;
    }

    /** 确认执行结果（前端回填会话用）。 */
    public record ConfirmOutcome(String proposalId, String summary, String batchId) {
    }

    /**
     * 用户确认 → 原子取出提案 → 权限终审 → 按快照执行写。
     *
     * @throws BusinessException 提案不存在/过期/已处理/越权，或文档无编辑权限
     */
    public ConfirmOutcome confirm(String proposalId, CurrentUserService.CurrentUser user) {
        PendingProposalStore.PendingProposal proposal = proposalStore.confirmForExecution(proposalId, user.userId());
        // 完整权限终审（提案时的轻校验是体验优化，这道门才是安全边界）
        permissionService.requireEdit(proposal.documentId(), user);
        String batchId = operationLogService.recordBatchEditAndApply(
                proposal.documentId(), proposal.updates(), user.userId(), user.displayName());
        log.info("proposal.confirm: {} executed by user={} batchId={} summary={}",
                proposal.proposalId(), user.userId(), batchId, proposal.summary());
        return new ConfirmOutcome(proposal.proposalId(), proposal.summary(), batchId);
    }

    /** 取消提案（已执行/已过期返回 false——取消是尽力而为，不抛错）。 */
    public boolean cancel(String proposalId, CurrentUserService.CurrentUser user) {
        return proposalStore.cancel(proposalId, user.userId());
    }

    /** 查看提案（前端卡片过期前的状态确认；不存在/非本人返回 null）。 */
    public PendingProposalStore.PendingProposal find(String proposalId, CurrentUserService.CurrentUser user) {
        return proposalStore.find(proposalId, user.userId());
    }
}
