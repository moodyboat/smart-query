package com.smartquery.tool;

import com.smartquery.engine.ReActEvent;
import java.util.function.Consumer;

/**
 * 工具执行上下文
 */
public record ToolExecutionContext(
    Long conversationId,
    Long dataSourceId,
    String traceId,
    String model,
    java.util.function.BooleanSupplier abortChecker,
    Consumer<ReActEvent> eventConsumer
) {
    public boolean isAborted() {
        return abortChecker.getAsBoolean();
    }

    public void emitEvent(ReActEvent event) {
        if (eventConsumer != null) {
            eventConsumer.accept(event);
        }
    }
}
