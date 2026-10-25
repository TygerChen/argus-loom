package com.argus.rag.qa.controller;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.logging.OperationLog;
import com.argus.rag.qa.model.vo.QaRecordDetailVO;
import com.argus.rag.qa.model.vo.QaRecordPageVO;
import com.argus.rag.qa.service.QaRecordPersistenceService;
import com.argus.rag.qa.service.QaRecordQueryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** QA 问答历史查询接口。 */
@OperationLog
@RestController
@RequestMapping("/api/qa/records")
public class QaRecordController {

    private final QaRecordQueryService qaRecordQueryService;
    private final QaRecordPersistenceService qaRecordPersistenceService;

    public QaRecordController(QaRecordQueryService qaRecordQueryService,
            QaRecordPersistenceService qaRecordPersistenceService) {
        this.qaRecordQueryService = qaRecordQueryService;
        this.qaRecordPersistenceService = qaRecordPersistenceService;
    }

    /** 分页查询当前用户可见的 QA 问答历史。 */
    @GetMapping
    public ApiResponse<QaRecordPageVO> listRecords(
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Boolean answered,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(qaRecordQueryService.list(groupId, answered, page, pageSize));
    }

    /** 查询单条 QA 问答记录详情及其引用快照。 */
    @GetMapping("/{recordId}")
    public ApiResponse<QaRecordDetailVO> getRecord(@PathVariable Long recordId) {
        return ApiResponse.success(qaRecordQueryService.getDetail(recordId));
    }

    /** 删除该条问答历史记录及其持久化的引用快照。 */
    @PutMapping("/{recordId}/rate")
    public ApiResponse<Void> rate(
            @PathVariable Long recordId,
            @RequestParam String rating
    ) {
        if (!"THUMBS_UP".equals(rating) && !"THUMBS_DOWN".equals(rating)
                && !"BAD_CITATION".equals(rating)) {
            throw new BusinessException("无效的评分值");
        }
        qaRecordPersistenceService.updateRating(recordId, rating);
        return ApiResponse.success(null);
    }
    /** 删除当前用户可见的 QA 历史记录及其引用快照。 */
    @DeleteMapping("/{recordId}")
    public ApiResponse<Void> deleteRecord(@PathVariable Long recordId) {
        qaRecordQueryService.delete(recordId);
        return ApiResponse.success(null);
    }
}
