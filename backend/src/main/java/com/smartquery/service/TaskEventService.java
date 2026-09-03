package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.TaskEvent;
import com.smartquery.mapper.TaskEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent task event bus. Producers write actual state changes once; SSE
 * connections subscribe without polling the model tables or occupying worker
 * threads. Database event ids provide Last-Event-ID replay after reconnects and
 * application restarts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskEventService {

    private final TaskEventMapper taskEventMapper;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Subscription>> subscribers =
        new ConcurrentHashMap<>();
    private final AtomicLong scanCursor = new AtomicLong();

    @Value("${smart-query.sse.timeout-ms:600000}")
    private long timeoutMs;

    @jakarta.annotation.PostConstruct
    void initialiseCursor() {
        TaskEvent latest = taskEventMapper.selectOne(new LambdaQueryWrapper<TaskEvent>()
            .orderByDesc(TaskEvent::getId).last("LIMIT 1"));
        if (latest != null && latest.getId() != null) scanCursor.set(latest.getId());
    }

    public static String trainingTopic(Long executionId) {
        return "training:" + executionId;
    }

    public static String modelTopic(Long modelId) {
        return "model:" + modelId;
    }

    public static String pipelineTopic(Long pipelineId, String runId) {
        return "pipeline:" + pipelineId + ":" + runId;
    }

    public static String orchestrationTopic(Long runId) {
        return "orchestration:" + runId;
    }

    public TaskEvent publish(String topic, String ownerUserId, String eventName,
                             Map<String, Object> payload, boolean terminal) {
        try {
            TaskEvent event = new TaskEvent();
            event.setTopic(topic);
            event.setOwnerUserId(ownerUserId);
            event.setEventName(eventName);
            event.setPayload(objectMapper.writeValueAsString(payload));
            event.setTerminal(terminal);
            taskEventMapper.insert(event);
            deliver(event);
            return event;
        } catch (Exception e) {
            // Event delivery must not change the training result. Durable task
            // state remains in its execution/model record even if event storage fails.
            log.error("[TASK-EVENT] publish failed topic={} name={}: {}", topic, eventName, e.getMessage(), e);
            return null;
        }
    }

    public SseEmitter subscribe(String topic, String ownerUserId, Long lastEventId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Subscription subscription = new Subscription(ownerUserId, emitter,
            new AtomicLong(lastEventId == null ? 0L : lastEventId));
        subscribers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(subscription);
        Runnable cleanup = () -> remove(topic, subscription);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(ignored -> cleanup.run());

        try {
            List<TaskEvent> replay = taskEventMapper.selectList(
                new LambdaQueryWrapper<TaskEvent>()
                    .eq(TaskEvent::getTopic, topic)
                    .eq(TaskEvent::getOwnerUserId, ownerUserId)
                    .gt(lastEventId != null, TaskEvent::getId, lastEventId)
                    .orderByAsc(TaskEvent::getId)
                    .last("LIMIT 500"));
            for (TaskEvent event : replay) {
                if (!send(subscription, event)) break;
            }
            if (replay.isEmpty()) {
                emitter.send(SseEmitter.event().name("connected").data(Map.of("topic", topic)));
            }
        } catch (Exception e) {
            remove(topic, subscription);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void deliver(TaskEvent event) {
        List<Subscription> topicSubscribers = subscribers.get(event.getTopic());
        if (topicSubscribers == null) return;
        for (Subscription subscription : topicSubscribers) {
            if (!event.getOwnerUserId().equals(subscription.ownerUserId()) || !send(subscription, event)) {
                remove(event.getTopic(), subscription);
            }
        }
    }

    private boolean send(Subscription subscription, TaskEvent event) {
        try {
            long previous = subscription.lastSentId().get();
            if (event.getId() != null && event.getId() <= previous) return true;
            if (event.getId() != null && !subscription.lastSentId().compareAndSet(previous, event.getId())) {
                return send(subscription, event);
            }
            Map<String, Object> payload = objectMapper.readValue(event.getPayload(), new TypeReference<>() {});
            subscription.emitter().send(SseEmitter.event()
                .id(String.valueOf(event.getId()))
                .name(event.getEventName())
                .data(payload));
            if (Boolean.TRUE.equals(event.getTerminal())) {
                subscription.emitter().complete();
                return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** One global heartbeat loop replaces one sleeping thread per connection. */
    @Scheduled(fixedRateString = "${smart-query.sse.heartbeat-ms:15000}")
    public void heartbeat() {
        subscribers.forEach((topic, list) -> list.forEach(subscription -> {
            try {
                subscription.emitter().send(SseEmitter.event()
                    .name("heartbeat")
                    .data(Map.of("time", LocalDateTime.now().toString())));
            } catch (IOException e) {
                remove(topic, subscription);
            }
        }));
    }

    /** Database-backed fan-out lets another application instance execute the
     * task while this instance owns the SSE connection. This is one lightweight
     * poll per instance, not one loop per client connection. */
    @Scheduled(fixedDelayString = "${smart-query.sse.event-scan-ms:1000}")
    public void fanOutRemoteEvents() {
        if (subscribers.isEmpty()) return;
        List<TaskEvent> events = taskEventMapper.selectList(new LambdaQueryWrapper<TaskEvent>()
            .gt(TaskEvent::getId, scanCursor.get())
            .orderByAsc(TaskEvent::getId)
            .last("LIMIT 500"));
        for (TaskEvent event : events) {
            scanCursor.accumulateAndGet(event.getId(), Math::max);
            deliver(event);
        }
    }

    @Scheduled(cron = "${smart-query.sse.event-retention-cron:0 30 3 * * *}")
    public void purgeExpiredEvents() {
        taskEventMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskEvent>()
            .lt(TaskEvent::getCreatedAt, LocalDateTime.now().minusDays(7)));
    }

    private void remove(String topic, Subscription subscription) {
        CopyOnWriteArrayList<Subscription> list = subscribers.get(topic);
        if (list == null) return;
        list.remove(subscription);
        if (list.isEmpty()) subscribers.remove(topic, list);
    }

    private record Subscription(String ownerUserId, SseEmitter emitter, AtomicLong lastSentId) {}
}
