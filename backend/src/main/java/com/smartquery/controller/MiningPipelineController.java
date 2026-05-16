package com.smartquery.controller;

import com.smartquery.common.BusinessException;
import com.smartquery.common.Result;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.MiningPipeline;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.MiningPipelineMapper;
import com.smartquery.service.MiningService;
import com.smartquery.service.PipelineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/mining/pipeline")
@RequiredArgsConstructor
public class MiningPipelineController {

    private final MiningPipelineMapper miningPipelineMapper;
    private final MiningModelMapper miningModelMapper;
    private final PipelineService pipelineService;
    private final MiningService miningService;

    @GetMapping
    public Result<List<MiningPipeline>> list(
            @RequestParam(required = false) Long dataSourceId) {
        LambdaQueryWrapper<MiningPipeline> wrapper = new LambdaQueryWrapper<MiningPipeline>()
                .eq(MiningPipeline::getDeleted, 0)
                .orderByDesc(MiningPipeline::getCreatedAt);
        if (dataSourceId != null) wrapper.eq(MiningPipeline::getDataSourceId, dataSourceId);
        return Result.ok(miningPipelineMapper.selectList(wrapper));
    }

    @GetMapping("/{id}")
    public Result<MiningPipeline> get(@PathVariable Long id) {
        MiningPipeline pipeline = miningPipelineMapper.selectById(id);
        if (pipeline == null || Integer.valueOf(1).equals(pipeline.getDeleted())) {
            throw new BusinessException("流水线不存在: " + id);
        }
        return Result.ok(pipeline);
    }

    @PostMapping
    public Result<MiningPipeline> create(@RequestBody MiningPipeline pipeline) {
        pipeline.setStatus("draft");
        pipeline.setDeleted(0);
        miningPipelineMapper.insert(pipeline);
        return Result.ok(pipeline);
    }

    @PutMapping("/{id}")
    public Result<MiningPipeline> update(@PathVariable Long id, @RequestBody MiningPipeline updates) {
        MiningPipeline existing = miningPipelineMapper.selectById(id);
        if (existing == null || Integer.valueOf(1).equals(existing.getDeleted())) {
            throw new BusinessException("流水线不存在: " + id);
        }
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getNodes() != null) existing.setNodes(updates.getNodes());
        if (updates.getEdges() != null) existing.setEdges(updates.getEdges());
        if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
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
        MiningPipeline pipeline = miningPipelineMapper.selectById(id);
        if (pipeline == null || Integer.valueOf(1).equals(pipeline.getDeleted())) {
            throw new BusinessException("流水线不存在: " + id);
        }
        if ("running".equals(pipeline.getStatus())) {
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
        return Result.ok(pipelineService.executePipeline(id));
    }

    @PostMapping("/{id}/validate")
    public Result<Map<String, Object>> validate(@PathVariable Long id) {
        return Result.ok(pipelineService.validatePipeline(id));
    }

    @PostMapping("/{id}/preview-step")
    public Result<Map<String, Object>> previewStep(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        String nodeId = (String) body.get("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            throw new BusinessException("nodeId 不能为空");
        }
        return Result.ok(pipelineService.previewStep(id, nodeId));
    }

    @GetMapping("/{id}/sync-status")
    public Result<Map<String, Object>> syncStatus(@PathVariable Long id) {
        MiningPipeline pipeline = miningPipelineMapper.selectById(id);
        if (pipeline == null || Integer.valueOf(1).equals(pipeline.getDeleted())) {
            throw new BusinessException("流水线不存在: " + id);
        }

        MiningModel model = miningModelMapper.selectOne(
            new LambdaQueryWrapper<MiningModel>()
                .eq(MiningModel::getPipelineId, id)
                .eq(MiningModel::getDeleted, 0)
                .last("LIMIT 1"));

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
