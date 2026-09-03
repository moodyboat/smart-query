package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.Lead;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.mapper.LeadMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.mapper.OutputArtifactMapper;
import com.smartquery.mapper.OutputArtifactRowMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;

class RunOutputCommitServiceTest {
    private final LeadService leadService = mock(LeadService.class);
    private final OutputArtifactMapper artifactMapper = mock(OutputArtifactMapper.class);
    private final OutputArtifactRowMapper rowMapper = mock(OutputArtifactRowMapper.class);
    private final OrchestrationRunMapper runMapper = mock(OrchestrationRunMapper.class);
    private final LeadMapper leadMapper = mock(LeadMapper.class);
    private final OutputArtifactIndexService outputIndex = mock(OutputArtifactIndexService.class);
    private final StorageGovernanceService storageGovernance = mock(StorageGovernanceService.class);
    private final RunOutputCommitService service = new RunOutputCommitService(
        leadService, artifactMapper, rowMapper, runMapper, leadMapper, new ObjectMapper(), outputIndex,
        storageGovernance);

    @Test
    void lostLeaseBlocksBusinessOutputBeforeAnyInsert() {
        when(runMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThrows(RunControlRegistry.LeaseLostException.class,
            () -> service.commitRun(1L, "old-token", 2, 100, List.of(), List.of()));

        verify(leadService, never()).recordLeads(any());
    }

    @Test
    void successfulCommitCrossesBothTransactionFences() {
        when(runMapper.update(isNull(), any(Wrapper.class))).thenReturn(1, 1);
        when(leadService.recordLeads(List.of())).thenReturn(List.of());

        RunOutputCommitService.CommitResult result = service.commitRun(
            2L, "current-token", 3, 250, List.of(), List.of());

        assertTrue(result.leads().isEmpty());
        assertTrue(result.artifacts().isEmpty());
        verify(runMapper, org.mockito.Mockito.times(2)).update(isNull(), any(Wrapper.class));
    }

    @Test
    void legacyCommittedArtifactsAreFinalizedWithoutReexecution() {
        OutputArtifact artifact = new OutputArtifact();
        artifact.setId(88L);
        Lead lead = new Lead();
        lead.setId(77L);
        when(artifactMapper.selectList(any())).thenReturn(List.of(artifact));
        when(leadMapper.selectList(any())).thenReturn(List.of(lead));
        when(runMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        RunOutputCommitService.CommitResult result = service.finalizeExistingCommit(
            3L, "recovery-token", 4, 500).orElseThrow();

        assertEquals(77L, result.leads().get(0).getId());
        assertEquals(88L, result.artifacts().get(0).getId());
    }

    @Test
    void outputQuotaIsReservedBeforeArtifactPublication() {
        when(runMapper.update(isNull(), any(Wrapper.class))).thenReturn(1, 1);
        when(leadService.recordLeads(List.of())).thenReturn(List.of());
        when(storageGovernance.estimateOutputBytes(any(), any(), any())).thenReturn(120L);
        when(storageGovernance.retentionUntil(StorageGovernanceService.OUTPUT))
            .thenReturn(LocalDateTime.now().plusDays(90));
        doAnswer(invocation -> { ((OutputArtifact) invocation.getArgument(0)).setId(99L); return 1; })
            .when(artifactMapper).insert(any(OutputArtifact.class));
        RunOutputCommitService.ArtifactInput input = new RunOutputCommitService.ArtifactInput(
            2L, 3L, "7", "EXCEL", "READY", Map.of(), Map.of(), List.of());

        RunOutputCommitService.CommitResult result = service.commitRun(
            2L, "current-token", 1, 50, List.of(), List.of(input));

        verify(storageGovernance).reserveHot("7", StorageGovernanceService.OUTPUT, 120L);
        assertEquals(StorageGovernanceService.ACTIVE, result.artifacts().get(0).getArchiveStatus());
        assertEquals(1, result.artifacts().get(0).getUsageAccounted());
        verify(outputIndex).index(eq(99L), eq(List.of()));
    }
}
