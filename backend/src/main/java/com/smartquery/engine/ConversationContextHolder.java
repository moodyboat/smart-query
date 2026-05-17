package com.smartquery.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ConversationContextHolder {

    private static final ThreadLocal<Long> CONVERSATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> DATA_SOURCE_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

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

    public static void setTraceId(String id) {
        TRACE_ID.set(id);
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void clear() {
        CONVERSATION_ID.remove();
        DATA_SOURCE_ID.remove();
        TRACE_ID.remove();
    }

    @Slf4j
    @org.springframework.stereotype.Component
    public static class SessionManager {
        private final Map<Long, ActiveSession> activeSessions = new ConcurrentHashMap<>();
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final File sessionDir;

        @org.springframework.beans.factory.annotation.Value("${smart-query.session-timeout-minutes:30}")
        private volatile long sessionTimeoutMinutes;

        public SessionManager() {
            this.sessionDir = new File(System.getProperty("user.home"), ".smartquery/sessions");
        }

        @jakarta.annotation.PostConstruct
        void init() {
            recoverSessions();
        }

        public record ActiveSession(
            Long conversationId,
            Long dataSourceId,
            String userId,
            Instant startTime,
            String threadName
        ) {}

        public void register(Long conversationId, Long dataSourceId, String userId) {
            ActiveSession session = new ActiveSession(
                conversationId, dataSourceId, userId, Instant.now(),
                Thread.currentThread().getName());
            activeSessions.put(conversationId, session);
            persistSession(session);
        }

        public void unregister(Long conversationId) {
            activeSessions.remove(conversationId);
            deleteSessionFile(conversationId);
        }

        public void cleanupStaleSessions() {
            Instant cutoff = Instant.now().minusSeconds(sessionTimeoutMinutes * 60);
            activeSessions.entrySet().removeIf(e -> {
                if (e.getValue().startTime().isBefore(cutoff)) {
                    deleteSessionFile(e.getKey());
                    log.info("[SESSION] Expired stale session: conversationId={}", e.getKey());
                    return true;
                }
                return false;
            });
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

        private void persistSession(ActiveSession session) {
            synchronized (sessionDir) {
                try {
                    sessionDir.mkdirs();
                    File target = new File(sessionDir, session.conversationId() + ".json");
                    File tmp = new File(sessionDir, session.conversationId() + ".json.tmp");
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("conversationId", session.conversationId());
                    data.put("dataSourceId", session.dataSourceId());
                    data.put("userId", session.userId());
                    data.put("startTime", session.startTime().toString());
                    data.put("threadName", session.threadName());
                    objectMapper.writeValue(tmp, data);
                    if (!tmp.renameTo(target)) {
                        java.nio.file.Files.deleteIfExists(tmp.toPath());
                        objectMapper.writeValue(target, data);
                    }
                } catch (IOException e) {
                    log.debug("[SESSION] Failed to persist session {}: {}", session.conversationId(), e.getMessage());
                }
            }
        }

        private void deleteSessionFile(Long conversationId) {
            synchronized (sessionDir) {
                File f = new File(sessionDir, conversationId + ".json");
                if (f.exists()) f.delete();
            }
        }

        private void recoverSessions() {
            if (!sessionDir.exists()) return;
            File[] files = sessionDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return;
            for (File f : files) {
                try {
                    Map<String, Object> data = objectMapper.readValue(f, Map.class);
                    Long convId = ((Number) data.get("conversationId")).longValue();
                    Long dsId = data.get("dataSourceId") != null ? ((Number) data.get("dataSourceId")).longValue() : null;
                    String userId = (String) data.get("userId");
                    Instant start = Instant.parse((String) data.get("startTime"));
                    String thread = (String) data.get("threadName");
                    activeSessions.put(convId, new ActiveSession(convId, dsId, userId, start, thread));
                    log.info("[SESSION] Recovered session: conversationId={}", convId);
                } catch (Exception e) {
                    log.warn("[SESSION] Failed to recover session from {}: {}", f.getName(), e.getMessage());
                    f.delete();
                }
            }
        }
    }
}
