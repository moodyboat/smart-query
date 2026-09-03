package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperatorExecutorRegistryTest {

    @Test
    void reportsOnlyActuallyEnabledRuntimeTypes() {
        OperatorExecutorRegistry registry = new OperatorExecutorRegistry(
            List.of(new BuiltinInputOperatorExecutor(), new RuleDslOperatorExecutor()));

        assertEquals(List.of("BUILTIN", "RULE_DSL"), registry.enabledImplementationTypes());
        BusinessException error = assertThrows(BusinessException.class,
            () -> registry.require("SQL_AST"));
        assertEquals(422, error.getCode());
    }
}
