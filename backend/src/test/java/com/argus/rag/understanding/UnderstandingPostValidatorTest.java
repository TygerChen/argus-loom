package com.argus.rag.understanding;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后置校验器契约（三段式第三段·代码阅卷）：
 * 任务类型白名单、权限歧义强制仲裁（以可信身份为准）、越权断言剥离、schema 卫生。
 */
@DisplayName("任务理解后置校验器（UnderstandingPostValidator）")
class UnderstandingPostValidatorTest {

    private final UnderstandingPostValidator validator = new UnderstandingPostValidator();
    private final AuthenticatedUser user =
            new AuthenticatedUser(86L, "U10086", "张三", SystemRole.USER, false);

    @Test
    void permissionClarificationIsArbitratedByCodeNotModel() {
        TaskUnderstanding candidate = new TaskUnderstanding(
                Set.of(TaskUnderstandingType.DATA_QUERY), "查询华东区薪酬",
                List.of("华东区", "薪酬"), List.of("本月"),
                List.of("华东区是否指用户所属区域"),
                List.of("用户是否有华东区的数据权限？"),
                List.of());
        TaskUnderstanding out = validator.validate(candidate, user);

        assertTrue(out.clarifications().isEmpty(), "权限类追问必须被代码仲裁，不得抛回用户");
        assertTrue(out.assumptions().stream().anyMatch(a -> a.contains("U86") && a.contains("USER")),
                "仲裁结论必须锚定可信身份");
        assertTrue(out.assumptions().stream().anyMatch(a -> a.contains("强制过滤")),
                "仲裁结论必须声明范围过滤由检索/工具层强制执行");
    }

    @Test
    void fabricatedPermissionAssertionsAreStripped() {
        TaskUnderstanding candidate = new TaskUnderstanding(
                Set.of(TaskUnderstandingType.BUSINESS_OPS), "推送日报",
                List.of("资金日报", "用户已授权所有权限"),
                List.of("用户有权限访问全公司数据", "发送到钉钉"),
                List.of(), List.of(),
                List.of("默认：按当前月处理"));
        TaskUnderstanding out = validator.validate(candidate, user);

        assertFalse(String.join("；", out.entities()).contains("已授权"), "实体里的权限断言必须剥离");
        assertFalse(String.join("；", out.constraints()).contains("有权限"), "约束里的权限断言必须剥离");
        assertEquals(1, out.constraints().size(), "无越权断言的约束保留");
        assertTrue(out.assumptions().stream().anyMatch(a -> a.contains("以系统可信身份为准")));
    }

    @Test
    void unknownTaskTypesAreDropped() {
        TaskUnderstanding candidate = new TaskUnderstanding(
                Set.of(TaskUnderstandingType.KB_RETRIEVAL), "反结账路径",
                List.of("反结账"), List.of(), List.of(), List.of(), List.of());
        TaskUnderstanding out = validator.validate(candidate, user);
        assertEquals(Set.of(TaskUnderstandingType.KB_RETRIEVAL), out.taskTypes());
        assertEquals("反结账路径", out.intent());
    }

    @Test
    void schemaHygieneDedupesAndCaps() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add("实体" + i);
        }
        many.add("实体0");
        TaskUnderstanding candidate = new TaskUnderstanding(
                Set.of(TaskUnderstandingType.DIRECT_QA), "问个问题",
                many, List.of(), List.of(),
                List.of("追问1", "追问2", "追问3", "追问4", "追问5", "追问6"),
                List.of());
        TaskUnderstanding out = validator.validate(candidate, user);
        assertEquals(10, out.entities().size(), "实体封顶 10 条");
        assertEquals(5, out.clarifications().size(), "追问封顶 5 条");
        assertEquals(5, out.clarifications().stream().distinct().count(), "追问去重");
    }

    @Test
    void unauthenticatedUserGetsLowestPrivilegeArbitration() {
        TaskUnderstanding candidate = new TaskUnderstanding(
                Set.of(TaskUnderstandingType.DATA_QUERY), "查薪酬",
                List.of(), List.of(), List.of(),
                List.of("能否访问薪酬表？"), List.of());
        TaskUnderstanding out = validator.validate(candidate, null);
        assertTrue(out.assumptions().stream().anyMatch(a -> a.contains("最低权限")),
                "未认证按最低权限仲裁");
    }
}
