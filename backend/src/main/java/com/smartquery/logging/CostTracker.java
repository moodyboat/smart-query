package com.smartquery.logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 成本追踪器 — 直译 Claude Code cost-tracker.ts
 *
 * <p>翻译对照:
 * <pre>
 * TS: StoredCostState { totalCostUSD, totalAPIDuration, modelUsage: Map&lt;string, Usage&gt; }
 * Java: CostTracker with per-model token/cost accumulation
 * </pre>
 */
@Slf4j
@Component
public class CostTracker {

    private final Map<String, ModelUsage> modelUsageMap = new ConcurrentHashMap<>();
    private final AtomicLong totalApiDurationMs = new AtomicLong(0);

    public record ModelUsage(String model, long inputTokens, long outputTokens, double costUsd, int callCount) {
        public ModelUsage add(long inputTokens, long outputTokens, double costUsd) {
            return new ModelUsage(model,
                this.inputTokens + inputTokens,
                this.outputTokens + outputTokens,
                this.costUsd + costUsd,
                this.callCount + 1);
        }
    }

    public void trackUsage(String model, int inputTokens, int outputTokens, double costUsd, long durationMs) {
        modelUsageMap.compute(model, (k, existing) ->
            (existing != null ? existing : new ModelUsage(model, 0, 0, 0.0, 0))
                .add(inputTokens, outputTokens, costUsd));
        totalApiDurationMs.addAndGet(durationMs);
    }

    public double getTotalCost() {
        return modelUsageMap.values().stream()
            .mapToDouble(ModelUsage::costUsd)
            .sum();
    }

    public long getTotalTokens() {
        return modelUsageMap.values().stream()
            .mapToLong(mu -> mu.inputTokens() + mu.outputTokens())
            .sum();
    }

    public Map<String, ModelUsage> getModelUsageMap() {
        return Map.copyOf(modelUsageMap);
    }
}
