package com.smartquery.engine;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话级上下文持有器 — ThreadLocal 隔离
 *
 * <p>翻译 Claude Code AppStateStore + sessionContext:
 * 每个请求线程绑定对话上下文，防止跨对话状态泄漏。
 */
public class ConversationContextHolder {

    private static final ThreadLocal<Long> CONVERSATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> DATA_SOURCE_ID = new ThreadLocal<>();

    public static void setConversationId(Long id) {
        CONVERSATION_ID.set(id);
    }

    public static Long getConversationId() {
        return CONVERSATION_ID.get();
    }

    public static void setDataSourceId(Long id) {
        DATA_SOURCE_ID.set(id);
    }

    public static Long getDataSourceId() {
        return DATA_SOURCE_ID.get();
    }

    public static void clear() {
        CONVERSATION_ID.remove();
        DATA_SOURCE_ID.remove();
    }

    /**
     * 活跃会话管理 — 跟踪所有在线对话，支持资源监控
     */
    public static class SessionManager {
        private final Map<Long, ActiveSession> activeSessions = new ConcurrentHashMap<>();

        public record ActiveSession(
            Long conversationId,
            Long dataSourceId,
            String userId,
            Instant startTime,
            String threadName
        ) {}

        public void register(Long conversationId, Long dataSourceId, String userId) {
            activeSessions.put(conversationId, new ActiveSession(
                conversationId, dataSourceId, userId, Instant.now(),
                Thread.currentThread().getName()));
        }

        public void unregister(Long conversationId) {
            activeSessions.remove(conversationId);
        }

        public boolean isActive(Long conversationId) {
            return activeSessions.containsKey(conversationId);
        }

        public Map<Long, ActiveSession> getActiveSessions() {
            return Map.copyOf(activeSessions);
        }

        public int activeCount() {
            return activeSessions.size();
        }
    }
}
