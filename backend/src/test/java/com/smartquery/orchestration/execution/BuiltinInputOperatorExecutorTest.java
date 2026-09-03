package com.smartquery.orchestration.execution;

import com.smartquery.entity.OperatorVersion;
import com.smartquery.orchestration.OperatorTypes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuiltinInputOperatorExecutorTest {

    @Test
    void forwardsOnlySubmittedRunRecords() {
        OperatorExecutionContext context = new OperatorExecutionContext(1L, 2L, "input",
            OperatorTypes.DATA, new OperatorVersion(), Map.of("operation", "run_input"), Map.of(),
            Map.of("records", List.of(Map.of("id", 7))), Map.of());

        OperatorExecutionResult result = new BuiltinInputOperatorExecutor().execute(context);

        assertEquals(1, result.output().get("recordCount"));
        assertEquals(List.of(Map.of("id", 7)), result.output().get("records"));
    }
}
