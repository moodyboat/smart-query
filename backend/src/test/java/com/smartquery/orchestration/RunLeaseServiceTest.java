package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smartquery.entity.NodeRun;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.mapper.NodeRunMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunLeaseServiceTest {
    private final OrchestrationRunMapper runMapper = mock(OrchestrationRunMapper.class);
    private final NodeRunMapper nodeRunMapper = mock(NodeRunMapper.class);
    private final RunLeaseService service = new RunLeaseService(runMapper, nodeRunMapper);

    @Test
    void claimsQueuedRunWithNewFencingToken() {
        OrchestrationRun run = run(1L, RunStatus.QUEUED);
        when(runMapper.selectById(1L)).thenReturn(run);
        when(runMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        Optional<RunLeaseService.LeaseClaim> claim = service.claim(1L);

        assertTrue(claim.isPresent());
        assertEquals(1, claim.get().attemptNo());
        assertFalse(claim.get().recovered());
        assertFalse(claim.get().token().isBlank());
    }

    @Test
    void expiredRunningRunIsClaimedAsRecovery() {
        OrchestrationRun run = run(2L, RunStatus.RUNNING);
        run.setAttemptNo(2);
        run.setRecoveryCount(1);
        run.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(runMapper.selectById(2L)).thenReturn(run);
        when(runMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        RunLeaseService.LeaseClaim claim = service.claim(2L).orElseThrow();

        assertTrue(claim.recovered());
        assertEquals(3, claim.attemptNo());
    }

    @Test
    void liveLeaseCannotBeStolen() {
        OrchestrationRun run = run(3L, RunStatus.RUNNING);
        run.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(runMapper.selectById(3L)).thenReturn(run);

        assertTrue(service.claim(3L).isEmpty());
        verify(runMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void cancellationMakesRunTerminalAndCancelsActiveNodes() {
        OrchestrationRun running = run(4L, RunStatus.RUNNING);
        OrchestrationRun canceled = run(4L, RunStatus.CANCELED);
        when(runMapper.selectById(4L)).thenReturn(running, canceled);
        when(runMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(nodeRunMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        RunLeaseService.CancellationResult result = service.cancel(4L, "9");

        assertTrue(result.transitioned());
        assertEquals(RunStatus.CANCELED, result.run().getStatus());
        verify(nodeRunMapper).update(isNull(), any(Wrapper.class));
    }

    private OrchestrationRun run(Long id, String status) {
        OrchestrationRun run = new OrchestrationRun();
        run.setId(id);
        run.setStatus(status);
        run.setAttemptNo(0);
        run.setRecoveryCount(0);
        return run;
    }
}
