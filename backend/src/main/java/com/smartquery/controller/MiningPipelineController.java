package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.entity.MiningPipeline;
import com.smartquery.mapper.MiningPipelineMapper;
import com.smartquery.service.MiningService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/mining/pipeline")
@RequiredArgsConstructor
public class MiningPipelineController {

    private final MiningPipelineMapper miningPipelineMapper;
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
            return Result.error("流水线不存在: " + id);
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
            return Result.error("流水线不存在: " + id);
        }
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getNodes() != null) existing.setNodes(updates.getNodes());
        if (updates.getEdges() != null) existing.setEdges(updates.getEdges());
        if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
        miningPipelineMapper.updateById(existing);
        return Result.ok(existing);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        miningPipelineMapper.deleteById(id);
        return Result.ok();
    }

    @PostMapping("/{id}/execute")
    public Result<Map<String, Object>> execute(@PathVariable Long id) {
        try {
            Map<String, Object> result = miningService.executePipeline(id);
            return Result.ok(result);
        } catch (Exception e) {
            log.error("[MINING] Pipeline execution failed for {}: {}", id, e.getMessage());
            return Result.error("Pipeline执行失败: " + e.getMessage());
        }
    }
}
