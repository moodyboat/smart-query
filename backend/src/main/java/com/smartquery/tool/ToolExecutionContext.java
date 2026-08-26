package com.smartquery.tool;

import com.smartquery.common.UserContextHolder;
import com.smartquery.engine.ReActEvent;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 工具执行上下文
 *
 * @param allowedTables 场景表白名单（normalize 后的小写表名）；null/empty 表示不限
 */
public record ToolExecutionContext(
    Long conversationId,
    Long dataSourceId,
    String traceId,
    String model,
    Set<String> allowedTables,
    java.util.function.BooleanSupplier abortChecker,
    Consumer<ReActEvent> eventConsumer,
    UserContextHolder.UserContext actor
) {
    /** Backward-compatible constructor that captures the request actor explicitly. */
    public ToolExecutionContext(
        Long conversationId,
        Long dataSourceId,
        String traceId,
        String model,
        Set<String> allowedTables,
        java.util.function.BooleanSupplier abortChecker,
        Consumer<ReActEvent> eventConsumer
    ) {
        this(conversationId, dataSourceId, traceId, model, allowedTables,
            abortChecker, eventConsumer, UserContextHolder.get());
    }

    public boolean isAborted() {
        return abortChecker.getAsBoolean();
    }

    public void emitEvent(ReActEvent event) {
        if (eventConsumer != null) {
            eventConsumer.accept(event);
        }
    }
}
