package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.service.RoleService;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.Lead;
import com.smartquery.entity.LeadEvidence;
import com.smartquery.entity.LeadSourceSnapshot;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.LeadEvidenceMapper;
import com.smartquery.mapper.LeadMapper;
import com.smartquery.mapper.LeadSourceSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Standard lead persistence boundary shared by rule, ML and agent outputs. */
@Service
@RequiredArgsConstructor
public class LeadService {

    private final LeadMapper leadMapper;
    private final LeadSourceSnapshotMapper snapshotMapper;
    private final LeadEvidenceMapper evidenceMapper;
    private final FlowVersionMapper flowVersionMapper;
    private final ContentHashService contentHashService;
    private final ObjectMapper objectMapper;
    private final RoleService roleService;

    public List<Lead> list(String status, String leadType, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<Lead> query = new LambdaQueryWrapper<Lead>()
            .eq(Lead::getDeleted, 0)
            .orderByDesc(Lead::getCreatedAt)
            .last("LIMIT " + safeLimit);
        if (!isAdmin()) query.eq(Lead::getOwnerUserId, currentUserId());
        if (status != null && !status.isBlank()) query.eq(Lead::getStatus, status);
        if (leadType != null && !leadType.isBlank()) query.eq(Lead::getLeadType, leadType);
        return leadMapper.selectList(query);
    }

    public LeadDetail getDetail(Long leadId) {
        Lead lead = requireLead(leadId);
        LeadSourceSnapshot snapshot = lead.getSourceSnapshotId() == null
            ? null : snapshotMapper.selectById(lead.getSourceSnapshotId());
        List<LeadEvidence> evidence = evidenceMapper.selectList(new LambdaQueryWrapper<LeadEvidence>()
            .eq(LeadEvidence::getLeadId, leadId)
            .orderByAsc(LeadEvidence::getId));
        return new LeadDetail(lead, parseMap(lead.getAttributesData()), snapshot,
            snapshot == null ? Map.of() : parseMap(snapshot.getSnapshotData()), evidence);
    }

    public List<Lead> listByRun(Long runId) {
        LambdaQueryWrapper<Lead> query = new LambdaQueryWrapper<Lead>()
            .eq(Lead::getDeleted, 0)
            .eq(Lead::getRunId, runId)
            .orderByDesc(Lead::getCreatedAt);
        if (!isAdmin()) query.eq(Lead::getOwnerUserId, currentUserId());
        return leadMapper.selectList(query);
    }

    /** Called by execution workers after a lead_output node has completed. */
    @Transactional
    public Lead recordLead(LeadInput input) {
        if (input == null) throw new BusinessException("线索输入不能为空");
        FlowVersion flowVersion = flowVersionMapper.selectById(input.flowVersionId());
        if (flowVersion == null) throw new BusinessException(404, "流程版本不存在: " + input.flowVersionId());
        if (input.sourceSnapshot() == null || input.sourceSnapshot().isEmpty()) {
            throw new BusinessException("线索必须包含判断时的原始输入快照");
        }

        LeadSourceSnapshot snapshot = new LeadSourceSnapshot();
        snapshot.setDataSourceId(input.dataSourceId());
        snapshot.setSourceTable(input.sourceTable());
        snapshot.setPrimaryKeyColumn(input.primaryKeyColumn());
        snapshot.setPrimaryKeyValue(input.primaryKeyValue());
        snapshot.setSnapshotData(json(input.sourceSnapshot()));
        snapshot.setSnapshotHash(contentHashService.sha256(input.sourceSnapshot()));
        snapshotMapper.insert(snapshot);

        Lead lead = new Lead();
        lead.setLeadNo(nextLeadNo());
        lead.setLeadType(required(input.leadType(), "leadType"));
        lead.setOwnerUserId(required(input.ownerUserId(), "ownerUserId"));
        lead.setSubjectType(input.subjectType());
        lead.setSubjectId(input.subjectId());
        lead.setSubjectName(input.subjectName());
        lead.setDecisionScore(input.decisionScore());
        lead.setDecisionLevel(input.decisionLevel());
        lead.setDecisionThreshold(input.decisionThreshold());
        lead.setDecisionResult(input.decisionResult());
        lead.setFlowVersionId(input.flowVersionId());
        lead.setRunId(input.runId());
        lead.setSourceSnapshotId(snapshot.getId());
        lead.setAttributesData(json(input.attributes() == null ? Map.of() : input.attributes()));
        lead.setStatus("NEW");
        lead.setOccurredAt(input.occurredAt() == null ? LocalDateTime.now() : input.occurredAt());
        lead.setDeleted(0);
        leadMapper.insert(lead);

        if (input.evidence() != null) {
            for (EvidenceInput item : input.evidence()) {
                LeadEvidence evidence = new LeadEvidence();
                evidence.setLeadId(lead.getId());
                evidence.setNodeRunId(item.nodeRunId());
                evidence.setOperatorVersionId(item.operatorVersionId());
                evidence.setEvidenceKind(required(item.kind(), "evidence.kind"));
                evidence.setEvidenceName(item.name());
                evidence.setFieldName(item.field());
                evidence.setActualValue(item.actualValue());
                evidence.setConditionExpression(item.condition());
                evidence.setContribution(item.contribution());
                evidence.setSnippet(item.snippet());
                evidenceMapper.insert(evidence);
            }
        }
        return lead;
    }

    /** Persists all leads from a successful run atomically. */
    @Transactional
    public List<Lead> recordLeads(List<LeadInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        return inputs.stream().map(this::recordLead).toList();
    }

    private Lead requireLead(Long id) {
        Lead lead = id == null ? null : leadMapper.selectById(id);
        if (lead == null || Integer.valueOf(1).equals(lead.getDeleted())) {
            throw new BusinessException(404, "线索不存在: " + id);
        }
        if (!isAdmin() && !currentUserId().equals(lead.getOwnerUserId())) {
            throw new BusinessException(403, "无权访问该线索");
        }
        return lead;
    }

    private String nextLeadNo() {
        return "L" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return Map.of("_parseError", e.getMessage()); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException("线索数据序列化失败: " + e.getMessage()); }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new BusinessException(field + "不能为空");
        return value;
    }

    private String currentUserId() { return UserContextHolder.require().userId().toString(); }
    private boolean isAdmin() { return roleService.currentUserHas(PermissionCodes.RESOURCE_ACCESS_ALL); }

    public record LeadDetail(Lead lead, Map<String, Object> attributes,
                             LeadSourceSnapshot source, Map<String, Object> sourceSnapshot,
                             List<LeadEvidence> evidence) {}

    public record EvidenceInput(Long nodeRunId, Long operatorVersionId, String kind,
                                String name, String field, String actualValue,
                                String condition, Double contribution, String snippet) {}

    public record LeadInput(String leadType, String ownerUserId,
                            String subjectType, String subjectId, String subjectName,
                            Double decisionScore, String decisionLevel, Double decisionThreshold,
                            String decisionResult, Long flowVersionId, Long runId,
                            Long dataSourceId, String sourceTable, String primaryKeyColumn,
                            String primaryKeyValue, Map<String, Object> sourceSnapshot,
                            Map<String, Object> attributes, List<EvidenceInput> evidence,
                            LocalDateTime occurredAt) {}
}
