package com.smartquery.orchestration.execution;

import com.smartquery.entity.OperatorVersion;
import com.smartquery.orchestration.BoundedSqlQueryService;
import com.smartquery.orchestration.ContentHashService;
import com.smartquery.orchestration.OperatorTypes;
import com.smartquery.orchestration.SqlAstPolicyService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlAstOperatorExecutorTest {
    @Test
    void wrapsDatabaseRowsAsOriginalSourceSnapshots() {
        SqlAstPolicyService policy = mock(SqlAstPolicyService.class);
        BoundedSqlQueryService query = mock(BoundedSqlQueryService.class);
        SqlAstPolicyService.SqlAstSpec spec = new SqlAstPolicyService.SqlAstSpec(
            3L, "SELECT * FROM payments", Set.of("payments"), Set.of("payments"),
            100, 30, Map.of(), Set.of(), List.of("order_id"));
        when(policy.validate(any())).thenReturn(spec);
        when(policy.parameters(any(), any(), any())).thenReturn(Map.of());
        when(query.query(spec, Map.of())).thenReturn(List.of(Map.of(
            "order_id", "P-1", "amount", 1250)));
        SqlAstOperatorExecutor executor = new SqlAstOperatorExecutor(
            policy, query, new ContentHashService(new com.fasterxml.jackson.databind.ObjectMapper()));
        OperatorExecutionContext context = new OperatorExecutionContext(1L, 2L, "payments",
            OperatorTypes.DATA, new OperatorVersion(), Map.of(), Map.of(), Map.of(), Map.of());

        OperatorExecutionResult result = executor.execute(context);

        @SuppressWarnings("unchecked")
        Map<String, Object> record = ((List<Map<String, Object>>) result.output().get("records")).get(0);
        assertEquals(List.of(Map.of("order_id", "P-1", "amount", 1250)),
            record.get(LineageSupport.SOURCE_SNAPSHOTS));
        assertFalse(((List<?>) record.get(LineageSupport.SOURCE_REFS)).isEmpty());
        assertEquals("SQL_QUERY", ((Map<?, ?>) ((List<?>) record.get(LineageSupport.EVIDENCE)).get(0)).get("kind"));
    }
}
