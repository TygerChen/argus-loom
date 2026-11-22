package com.argus.rag.common.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感动作分类器契约：删除/导出/外发/批量改 → HIGH + 确认门；
 * 普通只读问题绝不误标；多类命中时就高不就低。
 */
@DisplayName("任务风险分类器（TaskRiskClassifier）")
class TaskRiskClassifierTest {

    @ParameterizedTest
    @CsvSource({
            "把所有员工薪酬导出给我, EXPORT",
            "导出这个月的全部流水, EXPORT",
            "删除这个知识库群组, DELETE",
            "帮我清空回收站, DELETE",
            "把今天的资金日报推送到钉钉群, EXTERNAL_SEND",
            "把这张表分享给财务部同事, EXTERNAL_SEND",
            "批量修改这些行的金额, BATCH_MODIFY",
            "把所有员工的考勤记录批量更新为异常, BATCH_MODIFY"
    })
    void flagsSensitiveActions(String text, String expectedAction) {
        TaskRiskClassifier.RiskMark mark = TaskRiskClassifier.classify(text);
        assertEquals(expectedAction, mark.actionType().name(), "动作类型应命中: " + text);
        assertEquals(TaskRiskLevel.HIGH, mark.riskLevel(), "敏感动作应标 HIGH");
        assertTrue(mark.needConfirmation(), "敏感动作应需要确认门");
    }

    @ParameterizedTest
    @CsvSource({
            "金蝶怎么反结账？",
            "应收单下推生成收款单的路径是什么",
            "帮我查一下华东区本月薪酬异常",
            "这个月的资金日报生成了吗？",
            "知识库里有哪些关于固定资产的文档"
    })
    void keepsNormalQuestionsLowRisk(String text) {
        TaskRiskClassifier.RiskMark mark = TaskRiskClassifier.classify(text);
        assertEquals(TaskActionType.READ, mark.actionType());
        assertEquals(TaskRiskLevel.LOW, mark.riskLevel());
        assertFalse(mark.needConfirmation());
    }

    @Test
    void deleteWinsOverOtherHits() {
        // 一句话同时命中"导出+推送+删除"：风险标签就高不就低 → DELETE
        TaskRiskClassifier.RiskMark mark =
                TaskRiskClassifier.classify("先导出全员工资，推送到钉钉，然后删除原始文件");
        assertEquals(TaskActionType.DELETE, mark.actionType());
        assertTrue(mark.needConfirmation());
    }

    @Test
    void blankInputIsReadOnly() {
        assertEquals(TaskActionType.READ, TaskRiskClassifier.classify(null).actionType());
        assertEquals(TaskRiskLevel.LOW, TaskRiskClassifier.classify("").riskLevel());
    }
}
