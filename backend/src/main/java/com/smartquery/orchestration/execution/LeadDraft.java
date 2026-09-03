package com.smartquery.orchestration.execution;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Executor-neutral lead result; persistence metadata is added by the run service. */
public record LeadDraft(String leadType,
                        String subjectType, String subjectId, String subjectName,
                        Double decisionScore, String decisionLevel,
                        Double decisionThreshold, String decisionResult,
                        Long dataSourceId, String sourceTable,
                        String primaryKeyColumn, String primaryKeyValue,
                        Map<String, Object> sourceSnapshot,
                        Map<String, Object> attributes,
                        List<EvidenceDraft> evidence,
                        LocalDateTime occurredAt) {

    public record EvidenceDraft(String kind, String name, String field,
                                String actualValue, String condition,
                                Double contribution, String snippet) {
    }
}
