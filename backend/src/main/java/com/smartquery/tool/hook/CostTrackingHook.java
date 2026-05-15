package com.smartquery.tool.hook;

import com.smartquery.logging.CostTracker;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 成本追踪 Hook — 工具执行后记录 token 消耗和成本
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostTrackingHook implements ToolHook {

    private final CostTracker costTracker;

    @Override
    public String name() { return "cost-tracking"; }

    @Override
    public int order() { return 50; }

    @Override
    public void afterToolCall(String toolName, Map<String, Object> input, ToolResult result, ToolExecutionContext context) {
        if (result == null) return;
        // 记录工具执行时长
        log.debug("[HOOK][cost-tracking] tool={}, duration={}ms, success={}",
            toolName, result.durationMs(), result.success());
    }
}
