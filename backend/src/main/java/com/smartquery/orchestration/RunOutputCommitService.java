package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.Lead;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.entity.OutputArtifactRow;
import com.smartquery.mapper.LeadMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.mapper.OutputArtifactMapper;
import com.smartquery.mapper.OutputArtifactRowMapper;
import com.smartquery.orchestration.execution.LineageSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;

/** Atomically commits all business outputs after every DAG node succeeds. */
@Service
@RequiredArgsConstructor
public class RunOutputCommitService {
    private final LeadService leadService;
    private final OutputArtifactMapper outputArtifactMapper;
    private final OutputArtifactRowMapper outputArtifactRowMapper;
    private final OrchestrationRunMapper runMapper;
    private final LeadMapper leadMapper;
    private final ObjectMapper objectMapper;
    private final OutputArtifactIndexService outputArtifactIndexService;
    private final StorageGovernanceService storageGovernanceService;

    /** Output rows and SUCCESS are committed together, fenced by the current lease token. */
    @Transactional
    public CommitResult commitRun(Long runId, String leaseToken, int nodeCount, long executionTimeMs,
                                  List<LeadService.LeadInput> leads, List<ArtifactInput> artifacts) {
        UpdateWrapper<OrchestrationRun> begin = new UpdateWrapper<>();
        begin.set("status", RunStatus.COMMITTING)
            .eq("id", runId)
            .eq("lease_token", leaseToken)
            .eq("status", RunStatus.RUNNING)
            .isNull("cancel_requested_at");
        if (runMapper.update(null, begin) != 1) {
            throw new RunControlRegistry.LeaseLostException();
        }
        List<Lead> savedLeads = leadService.recordLeads(leads);
        List<OutputArtifact> savedArtifacts = new ArrayList<>();
        for (ArtifactInput input : artifacts) {
            String contentSpec = json(input.contentSpec());
            String artifactData = json(input.artifactData());
            long payloadBytes = storageGovernanceService.estimateOutputBytes(
                contentSpec, artifactData, input.records());
            storageGovernanceService.reserveHot(input.ownerUserId(),
                StorageGovernanceService.OUTPUT, payloadBytes);
            OutputArtifact artifact = new OutputArtifact();
            artifact.setRunId(input.runId());
            artifact.setNodeRunId(input.nodeRunId());
            artifact.setOwnerUserId(input.ownerUserId());
            artifact.setOutputKind(input.outputKind());
            artifact.setStatus(input.status());
            artifact.setQueryIndexStatus("READY");
            artifact.setArchiveStatus(StorageGovernanceService.ACTIVE);
            artifact.setPayloadBytes(payloadBytes);
            artifact.setUsageAccounted(1);
            artifact.setRetentionUntil(retentionUntil(input.outputKind(), input.contentSpec()));
            artifact.setContentSpec(contentSpec);
            artifact.setArtifactData(artifactData);
            artifact.setFilePath(null);
            artifact.setMimeType(mimeType(input.outputKind()));
            outputArtifactMapper.insert(artifact);
            insertViewRows(artifact.getId(), input.records());
            outputArtifactIndexService.index(artifact.getId(), input.records());
            savedArtifacts.add(artifact);
        }
        completeRun(runId, leaseToken, nodeCount, executionTimeMs, savedLeads, savedArtifacts,
            RunStatus.COMMITTING);
        return new CommitResult(savedLeads, savedArtifacts);
    }

    /** Compatibility recovery for a crash between the legacy output commit and run status update. */
    @Transactional
    public Optional<CommitResult> finalizeExistingCommit(Long runId, String leaseToken,
                                                          int nodeCount, long executionTimeMs) {
        List<OutputArtifact> artifacts = outputArtifactMapper.selectList(
            new LambdaQueryWrapper<OutputArtifact>().eq(OutputArtifact::getRunId, runId)
                .orderByAsc(OutputArtifact::getId));
        if (artifacts.isEmpty()) return Optional.empty();
        List<Lead> leads = leadMapper.selectList(new LambdaQueryWrapper<Lead>()
            .eq(Lead::getRunId, runId).orderByAsc(Lead::getId));
        completeRun(runId, leaseToken, nodeCount, executionTimeMs, leads, artifacts, RunStatus.RUNNING);
        return Optional.of(new CommitResult(leads, artifacts));
    }

    private void completeRun(Long runId, String leaseToken, int nodeCount, long executionTimeMs,
                             List<Lead> leads, List<OutputArtifact> artifacts, String expectedStatus) {
        List<Long> leadIds = leads.stream().map(Lead::getId).toList();
        List<Long> artifactIds = artifacts.stream().map(OutputArtifact::getId).toList();
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("nodeCount", nodeCount);
        summary.put("leadCount", leadIds.size());
        summary.put("leadIds", leadIds);
        summary.put("artifactCount", artifactIds.size());
        summary.put("artifactIds", artifactIds);
        summary.put("executionTimeMs", executionTimeMs);
        UpdateWrapper<OrchestrationRun> finish = new UpdateWrapper<>();
        finish.set("status", RunStatus.SUCCESS)
            .set("output_summary", json(summary))
            .set("finished_at", LocalDateTime.now())
            .set("lease_owner", null)
            .set("lease_token", null)
            .set("lease_expires_at", null)
            .eq("id", runId)
            .eq("lease_token", leaseToken)
            .eq("status", expectedStatus)
            .isNull("cancel_requested_at");
        if (runMapper.update(null, finish) != 1) {
            throw new RunControlRegistry.LeaseLostException();
        }
    }

    private void insertViewRows(Long artifactId, List<Map<String, Object>> records) {
        if (records == null) return;
        for (int index = 0; index < records.size(); index++) {
            Map<String, Object> record = records.get(index);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            record.forEach((key, value) -> {
                if (!key.startsWith("__")) result.put(key, value);
            });
            OutputArtifactRow row = new OutputArtifactRow();
            row.setArtifactId(artifactId);
            row.setRowIndex(index);
            row.setResultData(json(result));
            row.setSourceData(json(list(record.get(LineageSupport.SOURCE_SNAPSHOTS))));
            row.setEvidenceData(json(list(record.get(LineageSupport.EVIDENCE))));
            row.setSourceRefs(json(list(record.get(LineageSupport.SOURCE_REFS))));
            outputArtifactRowMapper.insert(row);
        }
    }

    private List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw new BusinessException("输出产物序列化失败: " + e.getMessage()); }
    }

    private LocalDateTime retentionUntil(String outputKind, Object rawSpec) {
        if ("TEMP_RESULT".equalsIgnoreCase(outputKind) && rawSpec instanceof Map<?, ?> spec) {
            Object rawDays = spec.get("retentionDays");
            try {
                int days = Math.max(1, Math.min(30, Integer.parseInt(String.valueOf(rawDays))));
                return LocalDateTime.now().plusDays(days);
            } catch (Exception ignored) {
                return LocalDateTime.now().plusDays(7);
            }
        }
        return storageGovernanceService.retentionUntil(StorageGovernanceService.OUTPUT);
    }

    private String mimeType(String outputKind) {
        return switch (String.valueOf(outputKind).toUpperCase(java.util.Locale.ROOT)) {
            case "EXPORT_XLSX" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "EXPORT_CSV" -> "text/csv;charset=UTF-8";
            case "EXPORT_PDF" -> "application/pdf";
            case "EXPORT_JSON" -> "application/json";
            case "EXPORT_PNG" -> "image/png";
            default -> "application/vnd.smart-query.output+json";
        };
    }

    public record ArtifactInput(Long runId, Long nodeRunId, String ownerUserId,
                                String outputKind, String status,
                                Object contentSpec, Object artifactData,
                                List<Map<String, Object>> records) {}

    public record CommitResult(List<Lead> leads, List<OutputArtifact> artifacts) {}
}
