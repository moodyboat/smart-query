package com.smartquery.controller;

import com.smartquery.common.BusinessException;
import com.smartquery.common.Result;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.MiningPipeline;
import com.smartquery.mapper.MiningPipelineMapper;
import com.smartquery.service.MiningService;
import com.smartquery.service.PipelineService;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/v1/mining/pipeline")
@RequiredArgsConstructor
public class MiningPipelineController {

    private final MiningPipelineMapper miningPipelineMapper;
    private final PipelineService pipelineService;
    private final MiningService miningService;
    private final ResourceAccessService resourceAccess;
    private final com.smartquery.service.TaskEventService taskEventService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Qualifier("asyncExecutor")
    private final Executor asyncExecutor;

    @GetMapping
    public Result<List<MiningPipeline>> list(
            @RequestParam(required = false) Long dataSourceId) {
        return Result.ok(resourceAccess.listPipelines(dataSourceId));
    }

    @GetMapping("/{id}")
    public Result<MiningPipeline> get(@PathVariable Long id) {
        return Result.ok(resourceAccess.requirePipeline(id));
    }

    @PostMapping
    public Result<MiningPipeline> create(@RequestBody Map<String, Object> body) {
        MiningPipeline pipeline = new MiningPipeline();
        pipeline.setUserId(resourceAccess.currentUserId());
        pipeline.setName((String) body.getOrDefault("name", "新流程"));
        pipeline.setDescription((String) body.get("description"));
        if (body.get("dataSourceId") != null) {
            pipeline.setDataSourceId(((Number) body.get("dataSourceId")).longValue());
        } else {
            pipeline.setDataSourceId(0L);
        }
        if (body.get("conversationId") != null) {
            Long conversationId = ((Number) body.get("conversationId")).longValue();
            var conversation = resourceAccess.requireConversation(conversationId);
            if (conversation.getDataSourceId() != null
                    && !conversation.getDataSourceId().equals(pipeline.getDataSourceId())) {
                throw new BusinessException(403, "流水线数据源与会话绑定的数据源不一致");
            }
            pipeline.setConversationId(conversationId);
        }
        try {
            pipeline.setNodes(objectMapper.writeValueAsString(body.getOrDefault("nodes", List.of())));
            pipeline.setEdges(objectMapper.writeValueAsString(body.getOrDefault("edges", List.of())));
        } catch (Exception e) {
            throw new BusinessException("节点/边数据格式错误: " + e.getMessage());
        }
        if (body.get("sourceType") != null) pipeline.setSourceType((String) body.get("sourceType"));
        pipeline.setStatus(com.smartquery.common.ModelStatus.DRAFT);
        pipeline.setDeleted(0);
        miningPipelineMapper.insert(pipeline);
        return Result.ok(pipeline);
    }

    @PutMapping("/{id}")
    public Result<MiningPipeline> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        MiningPipeline existing = resourceAccess.requirePipeline(id);
        if (body.get("name") != null) existing.setName((String) body.get("name"));
        if (body.get("description") != null) existing.setDescription((String) body.get("description"));
        if (body.get("status") != null) existing.setStatus((String) body.get("status"));
        if (body.get("dataSourceId") != null) {
            Long nextDataSourceId = ((Number) body.get("dataSourceId")).longValue();
            if (existing.getConversationId() != null) {
                var conversation = resourceAccess.requireConversation(existing.getConversationId());
                if (conversation.getDataSourceId() != null
                        && !conversation.getDataSourceId().equals(nextDataSourceId)) {
                    throw new BusinessException(403, "流水线数据源与会话绑定的数据源不一致");
                }
            }
            existing.setDataSourceId(nextDataSourceId);
        }
        try {
            if (body.get("nodes") != null) existing.setNodes(objectMapper.writeValueAsString(body.get("nodes")));
            if (body.get("edges") != null) existing.setEdges(objectMapper.writeValueAsString(body.get("edges")));
        } catch (Exception e) {
            throw new BusinessException("节点/边数据格式错误: " + e.getMessage());
        }
        miningPipelineMapper.updateById(existing);

        if (existing.getNodes() != null) {
            try {
                miningService.syncPipelineToModel(id);
            } catch (Exception e) {
                log.warn("[PIPELINE] Failed to sync pipeline {} to model: {}", id, e.getMessage());
            }
        }

        return Result.ok(existing);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(id);
        if (com.smartquery.common.ModelStatus.EXEC_RUNNING.equals(pipeline.getStatus())) {
            throw new BusinessException("流水线正在运行中，无法删除");
        }
        miningPipelineMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningPipeline>()
                .eq(MiningPipeline::getId, id)
                .set(MiningPipeline::getDeleted, 1));
        return Result.ok();
    }

    @PostMapping("/{id}/execute")
    public Result<Map<String, Object>> execute(@PathVariable Long id) {
        resourceAccess.requirePipeline(id);
        return Result.ok(pipelineService.executePipeline(id));
    }

    @PostMapping("/{id}/execute-stream")
    public Result<Map<String, Object>> startExecuteStream(@PathVariable Long id) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(id);
        UserContextHolder.UserContext requestCtx = UserContextHolder.require();
        String streamOwner = requestCtx.userId().toString();
        String runId = java.util.UUID.randomUUID().toString();
        String topic = com.smartquery.service.TaskEventService.pipelineTopic(id, runId);
        taskEventService.publish(topic, streamOwner, "queued", Map.of(
            "type", "pipeline_queued", "pipelineId", id, "runId", runId), false);
        asyncExecutor.execute(() -> {
            try (UserContextHolder.Scope ignored = UserContextHolder.open(requestCtx)) {
                try {
                    pipelineService.executePipelineStreamed(id, (type, data) -> {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("type", type);
                        event.put("pipelineId", id);
                        event.put("runId", runId);
                        event.putAll(data);
                        boolean terminal = "pipeline_complete".equals(type) || "pipeline_error".equals(type);
                        taskEventService.publish(topic, streamOwner, type, event, terminal);
                    });
                } catch (Exception e) {
                    taskEventService.publish(topic, streamOwner, "pipeline_error", Map.of(
                        "type", "pipeline_error", "pipelineId", id, "runId", runId,
                        "error", e.getMessage() == null ? "Pipeline 执行失败" : e.getMessage()), true);
                }
            }
        });
        return Result.ok(Map.of("pipelineId", id, "runId", runId, "status", "queued"));
    }

    @GetMapping(value = "/{id}/execute-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeStream(@PathVariable Long id,
                                    @RequestParam String runId,
                                    @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        resourceAccess.requirePipeline(id);
        if (runId == null || runId.isBlank()) throw new BusinessException("runId 不能为空");
        return taskEventService.subscribe(
            com.smartquery.service.TaskEventService.pipelineTopic(id, runId),
            UserContextHolder.require().userId().toString(), lastEventId);
    }

    @PostMapping("/{id}/validate")
    public Result<Map<String, Object>> validate(@PathVariable Long id) {
        resourceAccess.requirePipeline(id);
        return Result.ok(pipelineService.validatePipeline(id));
    }

    @PostMapping("/{id}/preview-step")
    public Result<Map<String, Object>> previewStep(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        resourceAccess.requirePipeline(id);
        String nodeId = (String) body.get("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            throw new BusinessException("nodeId 不能为空");
        }
        return Result.ok(pipelineService.previewStep(id, nodeId));
    }

    @PostMapping("/{id}/trial-missing-strategy")
    public Result<Map<String, Object>> trialMissingStrategy(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        resourceAccess.requirePipeline(id);
        @SuppressWarnings("unchecked")
        Map<String, String> strategies = (Map<String, String>) body.get("columnStrategies");
        if (strategies == null || strategies.isEmpty()) {
            throw new BusinessException("columnStrategies 不能为空");
        }
        return Result.ok(pipelineService.previewTrialMissingStrategy(id, strategies));
    }

    @GetMapping("/{id}/step-script")
    public Result<Map<String, Object>> getStepScript(
            @PathVariable Long id,
            @RequestParam String nodeId) {
        resourceAccess.requirePipeline(id);
        if (nodeId == null || nodeId.isBlank()) {
            throw new BusinessException("nodeId 不能为空");
        }
        String script = pipelineService.getStepScript(id, nodeId);
        script = script.replaceAll("mysql\\+pymysql://([^:]+):[^@]+@", "mysql+pymysql://$1:***@");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("script", script);
        return Result.ok(result);
    }

    @GetMapping("/{id}/segmented-script")
    public Result<Map<String, Object>> getSegmentedScript(@PathVariable Long id) {
        resourceAccess.requirePipeline(id);
        return Result.ok(pipelineService.getSegmentedScript(id));
    }

    @GetMapping("/{id}/sync-status")
    public Result<Map<String, Object>> syncStatus(@PathVariable Long id) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(id);
        MiningModel model = resourceAccess.findModelByPipeline(id);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("pipelineId", id);
        status.put("hasLinkedModel", model != null);

        if (model != null) {
            status.put("modelId", model.getId());
            status.put("modelName", model.getName());
            status.put("modelUpdatedAt", model.getUpdatedAt());
            status.put("pipelineUpdatedAt", pipeline.getUpdatedAt());
            status.put("lastSyncedAt", pipeline.getLastSyncedAt());

            LocalDateTime synced = pipeline.getLastSyncedAt();
            LocalDateTime pUpdated = pipeline.getUpdatedAt();
            LocalDateTime mUpdated = model.getUpdatedAt();
            boolean inSync = synced != null
                && (pUpdated == null || !synced.isBefore(pUpdated))
                && (mUpdated == null || !synced.isBefore(mUpdated));
            status.put("inSync", inSync);
            if (!inSync) {
                status.put("reason", synced == null ? "从未同步" : "pipeline 或 model 已修改但未同步");
            }
        } else {
            status.put("inSync", true);
        }

        return Result.ok(status);
    }
}
