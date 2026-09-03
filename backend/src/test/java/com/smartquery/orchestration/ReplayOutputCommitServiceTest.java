package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.NodeReplay;
import com.smartquery.entity.NodeReplayChunk;
import com.smartquery.mapper.NodeReplayChunkMapper;
import com.smartquery.mapper.NodeReplayMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayOutputCommitServiceTest {
    private final NodeReplayMapper replayMapper = mock(NodeReplayMapper.class);
    private final NodeReplayChunkMapper chunkMapper = mock(NodeReplayChunkMapper.class);
    private final StorageGovernanceService storage = mock(StorageGovernanceService.class);
    private final ReplayOutputCommitService service = new ReplayOutputCommitService(
        replayMapper, chunkMapper, storage);

    @Test
    void quotaReservationChunkWriteAndSuccessPublicationAreOneCommitPath() {
        NodeReplay replay = replay();
        when(storage.retentionUntil(StorageGovernanceService.REPLAY)).thenReturn(LocalDateTime.now().plusDays(30));
        when(replayMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.commit(replay, "{\"records\":[{\"id\":1}]}", "hash", "{}", "{}", "safe", 50);

        verify(storage).reserveHot(org.mockito.ArgumentMatchers.eq("7"),
            org.mockito.ArgumentMatchers.eq(StorageGovernanceService.REPLAY),
            org.mockito.ArgumentMatchers.longThat(value -> value > 0));
        ArgumentCaptor<NodeReplayChunk> chunk = ArgumentCaptor.forClass(NodeReplayChunk.class);
        verify(chunkMapper).insert(chunk.capture());
        assertEquals(9L, chunk.getValue().getReplayId());
        assertEquals("REPLAY_OUTPUT", chunk.getValue().getPayloadKind());
        verify(replayMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void staleLeaseRejectsReplayPublication() {
        NodeReplay replay = replay();
        when(storage.retentionUntil(StorageGovernanceService.REPLAY)).thenReturn(LocalDateTime.now());
        when(replayMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThrows(BusinessException.class,
            () -> service.commit(replay, "{}", "hash", "{}", "{}", "safe", 10));
    }

    private NodeReplay replay() {
        NodeReplay replay = new NodeReplay();
        replay.setId(9L);
        replay.setOwnerUserId("7");
        replay.setAttemptNo(2);
        replay.setLeaseToken("lease");
        return replay;
    }
}
