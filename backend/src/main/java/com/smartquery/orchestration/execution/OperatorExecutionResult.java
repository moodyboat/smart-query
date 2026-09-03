package com.smartquery.orchestration.execution;

import java.util.List;
import java.util.Map;

public record OperatorExecutionResult(Map<String, Object> output,
                                      List<LeadDraft> leads,
                                      String executionLog) {
    public OperatorExecutionResult {
        output = output == null ? Map.of() : Map.copyOf(output);
        leads = leads == null ? List.of() : List.copyOf(leads);
        executionLog = executionLog == null ? "" : executionLog;
    }
}
