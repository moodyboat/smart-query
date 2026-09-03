package com.smartquery.orchestration.execution;

/** Runtime extension point. Each implementation type has exactly one executor. */
public interface OperatorExecutor {
    String implementationType();

    OperatorExecutionResult execute(OperatorExecutionContext context);
}
