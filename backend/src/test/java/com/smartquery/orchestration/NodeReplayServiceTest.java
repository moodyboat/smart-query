package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.support.TestRoles;
import com.smartquery.entity.NodeReplay;
import com.smartquery.entity.NodeRun;
import com.smartquery.entity.NodeRunSnapshot;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.NodeReplayChunkMapper;
import com.smartquery.mapper.NodeReplayMapper;
import com.smartquery.mapper.NodeRunMapper;
import com.smartquery.mapper.OperatorVersionMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.orchestration.execution.OperatorExecutorRegistry;
import com.smartquery.service.RoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeReplayServiceTest {
    private final NodeReplayMapper replays = mock(NodeReplayMapper.class);
    private final NodeReplayChunkMapper chunks = mock(NodeReplayChunkMapper.class);
    private final NodeRunSnapshotService snapshots = mock(NodeRunSnapshotService.class);
    private final OrchestrationRunMapper runs = mock(OrchestrationRunMapper.class);
    private final NodeRunMapper nodes = mock(NodeRunMapper.class);
    private final FlowVersionMapper flows = mock(FlowVersionMapper.class);
    private final OperatorVersionMapper versions = mock(OperatorVersionMapper.class);
    private final RuntimeProfileService runtimes = mock(RuntimeProfileService.class);
    private final OperatorExecutorRegistry executors = mock(OperatorExecutorRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContentHashService hashes = new ContentHashService(objectMapper);
    private final ScheduledExecutorService watchdog = mock(ScheduledExecutorService.class);
    private final ReplayOutputCommitService replayOutputCommit = mock(ReplayOutputCommitService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final Executor deferred = command -> { };
    private final NodeReplayService service = new NodeReplayService(replays, chunks, snapshots,
        runs, nodes, flows, versions, runtimes, executors, hashes,
        new NodeReplayDiffService(hashes), replayOutputCommit, objectMapper, roleService,
        deferred, deferred, watchdog);

    @AfterEach
    void clearUser() {
        UserContextHolder.clear();
    }

    @Test
    void creationCopiesEveryImmutableReplayBindingAndOriginalActorRole() {
        UserContextHolder.set(new UserContextHolder.UserContext(7L, "owner", TestRoles.USER));
        OrchestrationRun run = run("7");
        run.setActorRole("ANALYST");
        NodeRun node = node();
        NodeRunSnapshot snapshot = snapshot();
        when(runs.selectById(10L)).thenReturn(run);
        when(nodes.selectById(20L)).thenReturn(node);
        when(snapshots.requireByNodeRun(20L)).thenReturn(snapshot);
        doAnswer(invocation -> {
            ((NodeReplay) invocation.getArgument(0)).setId(40L);
            return 1;
        }).when(replays).insert(any(NodeReplay.class));

        NodeReplay created = service.create(10L, 20L);

        ArgumentCaptor<NodeReplay> inserted = ArgumentCaptor.forClass(NodeReplay.class);
        verify(replays).insert(inserted.capture());
        assertEquals(40L, created.getId());
        assertEquals(30L, inserted.getValue().getFlowVersionId());
        assertEquals("flow-hash", inserted.getValue().getFlowContentHash());
        assertEquals(31L, inserted.getValue().getOperatorVersionId());
        assertEquals("operator-hash", inserted.getValue().getOperatorVersionContentHash());
        assertEquals(32L, inserted.getValue().getRuntimeProfileId());
        assertEquals("sha256:image", inserted.getValue().getRuntimeImageDigest());
        assertEquals("input-hash", inserted.getValue().getInputHash());
        assertEquals("output-hash", inserted.getValue().getExpectedOutputHash());
        assertEquals("ANALYST", inserted.getValue().getActorRole());
        assertEquals(RunStatus.QUEUED, inserted.getValue().getStatus());
    }

    @Test
    void anotherUserCannotCreateReplayForTheRun() {
        UserContextHolder.set(new UserContextHolder.UserContext(8L, "other", TestRoles.USER));
        when(runs.selectById(10L)).thenReturn(run("7"));

        BusinessException error = assertThrows(BusinessException.class,
            () -> service.create(10L, 20L));

        assertEquals(403, error.getCode());
        verify(nodes, never()).selectById(any());
        verify(replays, never()).insert(any());
    }

    private OrchestrationRun run(String owner) {
        OrchestrationRun run = new OrchestrationRun();
        run.setId(10L);
        run.setFlowVersionId(30L);
        run.setOwnerUserId(owner);
        run.setActorRole(TestRoles.USER);
        run.setStatus(RunStatus.SUCCESS);
        return run;
    }

    private NodeRun node() {
        NodeRun node = new NodeRun();
        node.setId(20L);
        node.setRunId(10L);
        node.setNodeId("predict-overdue");
        node.setOperatorVersionId(31L);
        node.setStatus(RunStatus.SUCCESS);
        node.setTimeoutSeconds(60);
        return node;
    }

    private NodeRunSnapshot snapshot() {
        NodeRunSnapshot snapshot = new NodeRunSnapshot();
        snapshot.setId(21L);
        snapshot.setNodeRunId(20L);
        snapshot.setRunId(10L);
        snapshot.setFlowVersionId(30L);
        snapshot.setFlowContentHash("flow-hash");
        snapshot.setNodeId("predict-overdue");
        snapshot.setOperatorVersionId(31L);
        snapshot.setOperatorVersionContentHash("operator-hash");
        snapshot.setRuntimeProfileId(32L);
        snapshot.setRuntimeImageDigest("sha256:image");
        snapshot.setInputHash("input-hash");
        snapshot.setOutputHash("output-hash");
        snapshot.setStatus("COMPLETED");
        return snapshot;
    }
}
