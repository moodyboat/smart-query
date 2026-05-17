package com.smartquery.prompt;

import com.smartquery.service.OntologyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class OntologyContextBuilder {

    private final OntologyService ontologyService;

    @Value("${ontology-context.cache-ttl-ms:600000}")
    private long cacheTtlMs;

    @Value("${ontology-context.token-budget:4000}")
    private int tokenBudget;

    private static final int CHARS_PER_TOKEN = com.smartquery.common.TokenConstants.CHARS_PER_TOKEN;

    private record CachedEntry(String content, long cachedAt) {}

    private final ConcurrentHashMap<Long, CachedEntry> ontologyCache = new ConcurrentHashMap<>();

    /**
     * 构建本体上下文 (带缓存和 token 预算)
     */
    public String buildOntologyContext(Long dataSourceId) {
        if (dataSourceId == null) return null;

        CachedEntry cached = ontologyCache.get(dataSourceId);
        if (cached != null && System.currentTimeMillis() - cached.cachedAt < cacheTtlMs) {
            log.debug("[ONTOLOGY-CTX] cache hit for dataSourceId={}", dataSourceId);
            return cached.content;
        }

        String context = ontologyService.buildOntologyContext(dataSourceId, tokenBudget);

        if (context == null) {
            log.debug("[ONTOLOGY-CTX] no ontology for dataSourceId={}", dataSourceId);
            return null;
        }

        ontologyCache.put(dataSourceId, new CachedEntry(context, System.currentTimeMillis()));
        log.debug("[ONTOLOGY-CTX] built context for dataSourceId={}: {} chars, ~{} tokens",
            dataSourceId, context.length(), context.length() / CHARS_PER_TOKEN);

        return context;
    }

    /**
     * 检查数据源是否有本体配置
     */
    public boolean hasOntology(Long dataSourceId) {
        if (dataSourceId == null) return false;
        return buildOntologyContext(dataSourceId) != null;
    }

    public void clearCache() {
        ontologyCache.clear();
        log.info("[ONTOLOGY-CTX] cache cleared");
    }

    public void evictCache(Long dataSourceId) {
        ontologyCache.remove(dataSourceId);
        log.info("[ONTOLOGY-CTX] evicted cache for dataSourceId={}", dataSourceId);
    }

    public Map<String, Object> getCacheStats() {
        return Map.of("size", ontologyCache.size(), "ttlMs", cacheTtlMs);
    }
}
