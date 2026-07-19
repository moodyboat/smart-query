package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.service.ConversationSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 对话摘要管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/summary")
@RequiredArgsConstructor
public class SummaryController {

    private final ConversationSummaryService summaryService;

    /**
     * 清除过期的摘要缓存
     */
    @PostMapping("/cache/evict-expired")
    public Result<Void> evictExpiredCache() {
        summaryService.evictExpiredCache();
        return Result.ok();
    }

    /**
     * 清除指定会话的摘要缓存
     */
    @PostMapping("/cache/evict/{conversationId}")
    public Result<Void> evictCache(@PathVariable Long conversationId) {
        summaryService.evictCache(conversationId);
        return Result.ok();
    }

    /**
     * 获取摘要统计信息
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", summaryService.getCacheSize());
        stats.put("summaryEnabled", summaryService.isEnabled());
        return Result.ok(stats);
    }

    /**
     * 测试摘要生成
     */
    @PostMapping("/test/{conversationId}")
    public Result<Map<String, Object>> testSummary(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "${smart-query.llm.default-model:glm-5.1}") String model) {
        // 这里可以添加测试逻辑
        Map<String, Object> result = new HashMap<>();
        result.put("message", "摘要测试功能待实现");
        result.put("conversationId", conversationId);
        result.put("model", model);
        return Result.ok(result);
    }
}
