package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.mapper.ExcelDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Excel 文档列表对普通用户按 creator_id（用户 ID）过滤。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExcelDocumentService 列表可见性测试")
class ExcelDocumentServiceTest {

    @Mock
    private ExcelDocumentMapper documentMapper;

    private ExcelDocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new ExcelDocumentService(documentMapper);
        when(documentMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(new Page<>());
    }

    @Test
    @DisplayName("非管理员列表按 userId 过滤自己创建的文档")
    void shouldFilterOwnDocumentsByUserIdForNormalUser() {
        documentService.listAccessibleByPage(1, 20, 42L, false, List.<Long>of(), false);

        ArgumentCaptor<QueryWrapper<ExcelDocument>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(documentMapper).selectPage(any(Page.class), captor.capture());

        QueryWrapper<ExcelDocument> wrapper = captor.getValue();
        // 先触发 SQL 段渲染，MyBatis-Plus 的参数值按需登记。
        assertThat(wrapper.getExpression().getSqlSegment()).contains("creator_id");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("42");
    }
}
