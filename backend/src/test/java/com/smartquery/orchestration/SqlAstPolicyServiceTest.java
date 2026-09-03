package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.tool.SqlSafetyValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SqlAstPolicyServiceTest {
    private final DataSourceQueryPolicyService dataSourcePolicy = mock(DataSourceQueryPolicyService.class);
    private final SqlAstPolicyService service = new SqlAstPolicyService(
        SqlSafetyValidator.defaults(), dataSourcePolicy);

    @Test
    void acceptsPinnedSelectAndMergesOnlyNamedParameters() {
        SqlAstPolicyService.SqlAstSpec spec = service.validate(Map.of(
            "dataSourceId", 7,
            "sql", "SELECT order_id, amount FROM payments WHERE amount >= :minAmount",
            "allowedTables", List.of("payments"),
            "defaultParameters", Map.of("minAmount", 100),
            "sourceRefFields", List.of("order_id"),
            "maxRows", 200));

        Map<String, Object> parameters = service.parameters(spec,
            Map.of("parameters", Map.of("minAmount", 500)), Map.of());

        assertEquals(7L, spec.dataSourceId());
        assertEquals(200, spec.maxRows());
        assertEquals(Map.of("minAmount", 500), parameters);
    }

    @Test
    void rejectsMutationCommentsAndTablesOutsideVersionWhitelist() {
        assertThrows(BusinessException.class, () -> service.validate(Map.of(
            "dataSourceId", 7, "sql", "DELETE FROM payments",
            "allowedTables", List.of("payments"))));
        assertThrows(BusinessException.class, () -> service.validate(Map.of(
            "dataSourceId", 7, "sql", "SELECT * FROM payments -- bypass",
            "allowedTables", List.of("payments"))));
        assertThrows(BusinessException.class, () -> service.validate(Map.of(
            "dataSourceId", 7, "sql", "SELECT * FROM secrets",
            "allowedTables", List.of("payments"))));
    }

    @Test
    void rejectsMissingParameterAndNodePolicyOverride() {
        SqlAstPolicyService.SqlAstSpec spec = service.validate(Map.of(
            "dataSourceId", 7, "sql", "SELECT * FROM payments WHERE amount > :minimum",
            "allowedTables", List.of("payments")));

        assertThrows(BusinessException.class, () -> service.parameters(spec, Map.of(), Map.of()));
        assertThrows(BusinessException.class, () -> service.parameters(spec,
            Map.of("sql", "SELECT * FROM secrets"), Map.of("parameters", Map.of("minimum", 1))));
    }
}
