package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.common.UserRoles;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.mapper.OutputArtifactMapper;
import com.smartquery.mapper.OutputArtifactRowMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OutputArtifactQueryServiceTest {
    private final OutputArtifactMapper artifacts = mock(OutputArtifactMapper.class);
    private final OutputArtifactRowMapper rows = mock(OutputArtifactRowMapper.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutputQueryCursorCodec cursorCodec = mock(OutputQueryCursorCodec.class);
    private final OutputArtifactQueryService service = new OutputArtifactQueryService(
        artifacts, rows, jdbcTemplate, objectMapper,
        new ContentHashService(objectMapper), cursorCodec);

    @AfterEach
    void clearUser() {
        UserContextHolder.clear();
    }

    @Test
    void legacyArtifactKeepsReadingButRejectsFieldFiltering() {
        UserContextHolder.set(new UserContextHolder.UserContext(7L, "owner", UserRoles.USER));
        OutputArtifact artifact = artifact("LEGACY");
        when(artifacts.selectById(3L)).thenReturn(artifact);

        BusinessException error = assertThrows(BusinessException.class, () -> service.query(3L,
            new OutputArtifactQueryService.QueryRequest(50, null, List.of(
                new OutputArtifactQueryService.FilterRequest("amount", "GT", 100, null)), null)));

        assertEquals(409, error.getCode());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void fieldOutsideServerCatalogIsRejectedBeforeSqlComposition() throws Exception {
        UserContextHolder.set(new UserContextHolder.UserContext(7L, "owner", UserRoles.USER));
        when(artifacts.selectById(3L)).thenReturn(artifact("READY"));
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet result = mock(ResultSet.class);
            when(result.getString("field_path")).thenReturn("amount");
            when(result.getString("value_type")).thenReturn("NUMBER");
            when(result.getLong("observed_count")).thenReturn(5L);
            handler.processRow(result);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        BusinessException error = assertThrows(BusinessException.class, () -> service.query(3L,
            new OutputArtifactQueryService.QueryRequest(50, null, List.of(
                new OutputArtifactQueryService.FilterRequest("amount) OR 1=1 --", "EQ", 1, null)), null)));

        assertEquals(422, error.getCode());
        verify(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validTextFilterUsesFullTextAndBoundEscapedValue() throws Exception {
        UserContextHolder.set(new UserContextHolder.UserContext(7L, "owner", UserRoles.USER));
        when(artifacts.selectById(3L)).thenReturn(artifact("READY"));
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet result = mock(ResultSet.class);
            when(result.getString("field_path")).thenReturn("memo");
            when(result.getString("value_type")).thenReturn("STRING");
            when(result.getLong("observed_count")).thenReturn(1L);
            handler.processRow(result);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
            .thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());

        OutputArtifactQueryService.QueryResult result = service.query(3L,
            new OutputArtifactQueryService.QueryRequest(50, null, List.of(
                new OutputArtifactQueryService.FilterRequest("memo", "CONTAINS", "50%_! paid", null)), null));

        assertEquals(1L, result.totalRows());
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class), arguments.capture());
        assertTrue(sql.getValue().contains("text_value LIKE ? ESCAPE '!'"));
        assertFalse(sql.getValue().contains("50%_! paid"));
        assertEquals("%50!%!_!! paid%", arguments.getValue()[2]);
    }

    private OutputArtifact artifact(String indexStatus) {
        OutputArtifact artifact = new OutputArtifact();
        artifact.setId(3L);
        artifact.setOwnerUserId("7");
        artifact.setQueryIndexStatus(indexStatus);
        artifact.setContentSpec("{}");
        artifact.setArtifactData("{}");
        return artifact;
    }
}
