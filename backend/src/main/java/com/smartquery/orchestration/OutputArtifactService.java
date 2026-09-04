package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.service.RoleService;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.entity.OutputArtifactRow;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.mapper.OutputArtifactMapper;
import com.smartquery.mapper.OutputArtifactRowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OutputArtifactService {
    private final OutputArtifactMapper outputArtifactMapper;
    private final OutputArtifactRowMapper outputArtifactRowMapper;
    private final OrchestrationRunMapper runMapper;
    private final ObjectMapper objectMapper;
    private final RoleService roleService;

    public List<OutputArtifact> list(Long runId) {
        OrchestrationRun run = runId == null ? null : runMapper.selectById(runId);
        if (run == null) throw new BusinessException(404, "编排运行不存在: " + runId);
        String userId = UserContextHolder.require().userId().toString();
        if (!roleService.currentUserHas(PermissionCodes.RESOURCE_ACCESS_ALL)
                && !userId.equals(run.getOwnerUserId())) {
            throw new BusinessException(403, "无权访问该运行输出");
        }
        return outputArtifactMapper.selectList(new LambdaQueryWrapper<OutputArtifact>()
            .eq(OutputArtifact::getRunId, runId)
            .eq(OutputArtifact::getArchiveStatus, StorageGovernanceService.ACTIVE)
            .orderByAsc(OutputArtifact::getId));
    }

    /** Lists recent renderable outputs for the result center. */
    public List<OutputArtifact> listRecent(String outputKind, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<OutputArtifact> query = new LambdaQueryWrapper<OutputArtifact>()
            .eq(OutputArtifact::getStatus, "READY")
            .eq(OutputArtifact::getArchiveStatus, StorageGovernanceService.ACTIVE)
            .orderByDesc(OutputArtifact::getCreatedAt)
            .last("LIMIT " + safeLimit);
        if (!isAdmin()) query.eq(OutputArtifact::getOwnerUserId, currentUserId());
        if (outputKind != null && !outputKind.isBlank()) {
            query.eq(OutputArtifact::getOutputKind, outputKind.trim().toUpperCase());
        }
        return outputArtifactMapper.selectList(query);
    }

    /** Returns a page of display rows with result, original inputs and evidence kept separately. */
    @Transactional(readOnly = true)
    public OutputView view(Long artifactId, int page, int pageSize) {
        OutputArtifact artifact = requireArtifact(artifactId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(pageSize, 200));
        long total = outputArtifactRowMapper.selectCount(new LambdaQueryWrapper<OutputArtifactRow>()
            .eq(OutputArtifactRow::getArtifactId, artifactId));
        int offset = (safePage - 1) * safeSize;
        List<OutputArtifactRow> storedRows = outputArtifactRowMapper.selectList(
            new LambdaQueryWrapper<OutputArtifactRow>()
                .eq(OutputArtifactRow::getArtifactId, artifactId)
                .orderByAsc(OutputArtifactRow::getRowIndex)
                .last("LIMIT " + safeSize + " OFFSET " + offset));

        List<OutputViewRow> rows = storedRows.stream().map(this::toViewRow).toList();
        Map<String, Object> contentSpec = parseMap(artifact.getContentSpec());
        return new OutputView(artifact, contentSpec, parseMap(artifact.getArtifactData()),
            safePage, safeSize, total, columns(contentSpec, rows), rows);
    }

    private OutputArtifact requireArtifact(Long artifactId) {
        OutputArtifact artifact = artifactId == null ? null : outputArtifactMapper.selectById(artifactId);
        if (artifact == null) throw new BusinessException(404, "输出结果不存在: " + artifactId);
        if (!isAdmin() && !currentUserId().equals(artifact.getOwnerUserId())) {
            throw new BusinessException(403, "无权访问该输出结果");
        }
        if (StorageGovernanceService.ARCHIVED.equals(artifact.getArchiveStatus())) {
            throw new BusinessException(409, "输出结果已归档，请由具备运行治理权限的人员恢复后查看");
        }
        return artifact;
    }

    private OutputViewRow toViewRow(OutputArtifactRow row) {
        Map<String, Object> result = parseMap(row.getResultData());
        List<Map<String, Object>> sources = parseMaps(row.getSourceData());
        List<Map<String, Object>> evidence = parseMaps(row.getEvidenceData());
        List<String> sourceRefs = parseStrings(row.getSourceRefs());
        Map<String, Object> display = new LinkedHashMap<>();
        if (sources.size() == 1) display.putAll(sources.get(0));
        display.putAll(result);
        return new OutputViewRow(row.getRowIndex(), display, result, sources, evidence, sourceRefs);
    }

    private List<Map<String, Object>> columns(Map<String, Object> spec, List<OutputViewRow> rows) {
        Object rawColumns = spec.get("columns");
        List<Map<String, Object>> columns = new ArrayList<>();
        if (rawColumns instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof String field && !field.isBlank()) {
                    columns.add(Map.of("field", field, "title", field));
                } else if (raw instanceof Map<?, ?> map && map.get("field") != null) {
                    Map<String, Object> column = new LinkedHashMap<>();
                    map.forEach((key, value) -> column.put(String.valueOf(key), value));
                    column.putIfAbsent("title", String.valueOf(column.get("field")));
                    columns.add(column);
                }
            }
        }
        if (!columns.isEmpty()) return List.copyOf(columns);

        Set<String> fields = new LinkedHashSet<>();
        for (OutputViewRow row : rows) {
            for (Map.Entry<String, Object> entry : row.display().entrySet()) {
                if (fields.size() >= 50) break;
                if (isScalar(entry.getValue())) fields.add(entry.getKey());
            }
        }
        return fields.stream().map(field -> Map.<String, Object>of("field", field, "title", field)).toList();
    }

    private boolean isScalar(Object value) {
        return !(value instanceof Map<?, ?>) && !(value instanceof List<?>);
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { throw new BusinessException("输出视图数据损坏: " + e.getMessage()); }
    }

    private List<Map<String, Object>> parseMaps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { throw new BusinessException("输出视图明细损坏: " + e.getMessage()); }
    }

    private List<String> parseStrings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { throw new BusinessException("输出血缘数据损坏: " + e.getMessage()); }
    }

    private String currentUserId() { return UserContextHolder.require().userId().toString(); }
    private boolean isAdmin() { return roleService.currentUserHas(PermissionCodes.RESOURCE_ACCESS_ALL); }

    public record OutputView(OutputArtifact artifact, Map<String, Object> contentSpec,
                             Map<String, Object> summary, int page, int pageSize, long totalRows,
                             List<Map<String, Object>> columns, List<OutputViewRow> rows) {}

    public record OutputViewRow(int rowIndex, Map<String, Object> display,
                                Map<String, Object> result, List<Map<String, Object>> sources,
                                List<Map<String, Object>> evidence, List<String> sourceRefs) {}
}
