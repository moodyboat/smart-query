package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.ArchiveChunk;
import com.smartquery.entity.ArchiveRecord;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.entity.OutputArtifactCell;
import com.smartquery.entity.OutputArtifactRow;
import com.smartquery.entity.StoragePolicy;
import com.smartquery.entity.StorageUsage;
import com.smartquery.mapper.ArchiveChunkMapper;
import com.smartquery.mapper.ArchiveRecordMapper;
import com.smartquery.mapper.NodeReplayChunkMapper;
import com.smartquery.mapper.NodeReplayMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.mapper.OutputArtifactCellMapper;
import com.smartquery.mapper.OutputArtifactMapper;
import com.smartquery.mapper.OutputArtifactRowMapper;
import com.smartquery.mapper.StoragePolicyMapper;
import com.smartquery.mapper.StorageUsageMapper;
import com.smartquery.service.ResourceAccessService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class StorageGovernanceServiceTest {
    private final StoragePolicyMapper policies = mock(StoragePolicyMapper.class);
    private final StorageUsageMapper usages = mock(StorageUsageMapper.class);
    private final ArchiveRecordMapper archives = mock(ArchiveRecordMapper.class);
    private final ArchiveChunkMapper archiveChunks = mock(ArchiveChunkMapper.class);
    private final OutputArtifactMapper artifacts = mock(OutputArtifactMapper.class);
    private final OutputArtifactRowMapper rows = mock(OutputArtifactRowMapper.class);
    private final OutputArtifactCellMapper cells = mock(OutputArtifactCellMapper.class);
    private final NodeReplayMapper replays = mock(NodeReplayMapper.class);
    private final NodeReplayChunkMapper replayChunks = mock(NodeReplayChunkMapper.class);
    private final OrchestrationRunMapper runs = mock(OrchestrationRunMapper.class);
    private final ResourceAccessService access = mock(ResourceAccessService.class);
    private final StorageGovernanceService service = new StorageGovernanceService(
        policies, usages, archives, archiveChunks, artifacts, rows, cells, replays,
        replayChunks, runs, new ArchivePayloadCodec(new ObjectMapper()), new ObjectMapper(), access,
        mock(StorageHotDataViewService.class));

    @Test
    void reservationUpdatesTypedAndTotalCountersUnderQuota() {
        when(policies.selectById(1L)).thenReturn(policy(1000));
        StorageUsage usage = usage(400);
        when(usages.selectForUpdate("7")).thenReturn(usage);

        service.reserveHot("7", StorageGovernanceService.OUTPUT, 250);

        assertEquals(650L, usage.getHotBytes());
        assertEquals(250L, usage.getOutputHotBytes());
        verify(usages).updateById(usage);
    }

    @Test
    void reservationOverQuotaFailsBeforeCounterMutation() {
        when(policies.selectById(1L)).thenReturn(policy(500));
        StorageUsage usage = usage(400);
        when(usages.selectForUpdate("7")).thenReturn(usage);

        BusinessException error = assertThrows(BusinessException.class,
            () -> service.reserveHot("7", StorageGovernanceService.REPLAY, 101));

        assertEquals(413, error.getCode());
        assertEquals(400L, usage.getHotBytes());
        verify(usages, never()).updateById(any());
    }

    @Test
    void outputArchiveMovesCapacityAndRemovesHotDetails() {
        when(policies.selectById(1L)).thenReturn(policy(1000));
        StorageUsage usage = usage(100);
        usage.setOutputHotBytes(100L);
        when(usages.selectForUpdate("7")).thenReturn(usage);
        OutputArtifact artifact = new OutputArtifact();
        artifact.setId(3L);
        artifact.setOwnerUserId("7");
        artifact.setStatus("READY");
        artifact.setArchiveStatus(StorageGovernanceService.ACTIVE);
        artifact.setPayloadBytes(60L);
        artifact.setUsageAccounted(1);
        artifact.setContentSpec("{\"view\":\"table\"}");
        artifact.setArtifactData("{\"rows\":1}");
        when(artifacts.selectForUpdate(3L)).thenReturn(artifact);
        when(rows.selectList(any())).thenReturn(List.of());
        when(cells.selectList(any())).thenReturn(List.of());
        when(access.currentUserId()).thenReturn("1");
        doAnswer(invocation -> { ((ArchiveRecord) invocation.getArgument(0)).setId(8L); return 1; })
            .when(archives).insert(any(ArchiveRecord.class));

        ArchiveRecord archive = service.archiveOutput(3L, "manual");

        assertEquals(8L, archive.getId());
        assertEquals(StorageGovernanceService.ARCHIVED, artifact.getArchiveStatus());
        assertEquals("{}", artifact.getContentSpec());
        assertNull(artifact.getArtifactData());
        assertEquals(40L, usage.getHotBytes());
        verify(rows).delete(any());
        verify(cells).delete(any());
        verify(artifacts).updateById(artifact);
    }

    @Test
    void outputRestoreValidatesArchiveAndRehydratesHotPayload() {
        ObjectMapper mapper = new ObjectMapper();
        ArchivePayloadCodec codec = new ArchivePayloadCodec(mapper);
        OutputArtifactRow storedRow = new OutputArtifactRow();
        storedRow.setId(91L);
        storedRow.setArtifactId(3L);
        storedRow.setRowIndex(0);
        storedRow.setResultData("{\"risk\":true}");
        storedRow.setSourceData("[]");
        storedRow.setEvidenceData("[]");
        storedRow.setSourceRefs("[]");
        OutputArtifactCell storedCell = new OutputArtifactCell();
        storedCell.setId(92L);
        storedCell.setArtifactId(3L);
        storedCell.setRowIndex(0);
        storedCell.setFieldPath("risk");
        storedCell.setValueType("BOOLEAN");
        storedCell.setBooleanValue(1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 2);
        payload.put("contentSpec", "{\"view\":\"table\"}");
        payload.put("artifactData", "{\"rows\":1}");
        payload.put("rows", List.of(storedRow));
        payload.put("cells", List.of(storedCell));
        ArchivePayloadCodec.EncodedPayload encoded = codec.encode(payload);

        ArchiveRecord archive = new ArchiveRecord();
        archive.setId(8L);
        archive.setTargetType(StorageGovernanceService.OUTPUT);
        archive.setTargetId(3L);
        archive.setOwnerUserId("7");
        archive.setState("READY");
        archive.setPayloadFormat(encoded.format());
        archive.setOriginalBytes(encoded.originalBytes());
        archive.setStoredBytes(encoded.storedBytes());
        archive.setChecksum(encoded.checksum());
        archive.setChunkCount(encoded.chunks().size());
        List<ArchiveChunk> chunks = new ArrayList<>();
        for (int index = 0; index < encoded.chunks().size(); index++) {
            ArchiveChunk chunk = new ArchiveChunk();
            chunk.setArchiveId(8L);
            chunk.setChunkIndex(index);
            chunk.setPayloadText(encoded.chunks().get(index));
            chunks.add(chunk);
        }
        OutputArtifact artifact = new OutputArtifact();
        artifact.setId(3L);
        artifact.setOwnerUserId("7");
        artifact.setArchiveStatus(StorageGovernanceService.ARCHIVED);
        artifact.setPayloadBytes(60L);
        artifact.setContentSpec("{}");
        StorageUsage usage = usage(40);
        usage.setArchiveBytes(encoded.storedBytes());
        when(policies.selectById(1L)).thenReturn(policy(1000));
        when(archives.selectForUpdate(8L)).thenReturn(archive);
        when(archives.selectById(8L)).thenReturn(archive);
        when(archiveChunks.selectList(any())).thenReturn(chunks);
        when(artifacts.selectForUpdate(3L)).thenReturn(artifact);
        when(rows.selectCount(any())).thenReturn(0L);
        when(cells.selectCount(any())).thenReturn(0L);
        when(usages.selectForUpdate("7")).thenReturn(usage);
        when(access.currentUserId()).thenReturn("1");

        service.restore(8L);

        assertEquals("RESTORED", archive.getState());
        assertEquals(StorageGovernanceService.ACTIVE, artifact.getArchiveStatus());
        assertEquals("{\"view\":\"table\"}", artifact.getContentSpec());
        assertEquals("{\"rows\":1}", artifact.getArtifactData());
        assertEquals(100L, usage.getHotBytes());
        assertEquals(60L, usage.getOutputHotBytes());
        assertEquals(0L, usage.getArchiveBytes());
        verify(rows).insert(any(OutputArtifactRow.class));
        verify(cells).insert(any(OutputArtifactCell.class));
        verify(archiveChunks).delete(any());
    }

    private StoragePolicy policy(long hotQuota) {
        StoragePolicy policy = new StoragePolicy();
        policy.setId(1L);
        policy.setOutputRetentionDays(90);
        policy.setReplayRetentionDays(30);
        policy.setHotQuotaBytesPerUser(hotQuota);
        policy.setArchiveQuotaBytesPerUser(5000L);
        policy.setWarningPercent(80);
        policy.setAutoArchiveEnabled(1);
        return policy;
    }

    private StorageUsage usage(long hotBytes) {
        StorageUsage usage = new StorageUsage();
        usage.setOwnerUserId("7");
        usage.setHotBytes(hotBytes);
        usage.setArchiveBytes(0L);
        usage.setOutputHotBytes(0L);
        usage.setReplayHotBytes(0L);
        return usage;
    }
}
