package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.NodeRun;
import com.smartquery.entity.NodeRunSnapshot;
import com.smartquery.entity.NodeRunSnapshotChunk;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OperatorVersionRuntimeBinding;
import com.smartquery.entity.RuntimeProfile;
import com.smartquery.mapper.NodeRunSnapshotChunkMapper;
import com.smartquery.mapper.NodeRunSnapshotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeRunSnapshotServiceTest {
    private final NodeRunSnapshotMapper snapshots = mock(NodeRunSnapshotMapper.class);
    private final NodeRunSnapshotChunkMapper chunks = mock(NodeRunSnapshotChunkMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContentHashService hashes = new ContentHashService(objectMapper);
    private final NodeRunSnapshotService service =
        new NodeRunSnapshotService(snapshots, chunks, objectMapper, hashes);

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, NodeRunSnapshot.class);
        TableInfoHelper.initTableInfo(assistant, NodeRunSnapshotChunk.class);
    }

    @Test
    void capturesExactInputContractAndOriginalOutputInAttemptScopedChunks() {
        ReflectionTestUtils.setField(service, "maxSnapshotBytes", 1_000_000L);
        when(snapshots.selectOne(any())).thenReturn(null);
        when(snapshots.update(isNull(), any())).thenReturn(1);
        doAnswer(invocation -> {
            ((NodeRunSnapshot) invocation.getArgument(0)).setId(90L);
            return 1;
        }).when(snapshots).insert(any(NodeRunSnapshot.class));

        NodeRun nodeRun = new NodeRun();
        nodeRun.setId(20L);
        nodeRun.setRunId(10L);
        nodeRun.setNodeId("rule-1");
        nodeRun.setInputHash("input-hash");
        nodeRun.setAttemptNo(2);
        nodeRun.setLeaseToken("lease-2");
        FlowVersion flow = new FlowVersion();
        flow.setId(30L);
        flow.setContentHash("flow-hash");
        OperatorVersion version = new OperatorVersion();
        version.setId(31L);
        version.setContentHash("operator-hash");
        version.setImplementationType("RULE_DSL");
        version.setImplementationPayload("{}");
        OperatorDefinition definition = new OperatorDefinition();
        definition.setOperatorType(OperatorTypes.RULE);
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(32L);
        OperatorVersionRuntimeBinding binding = new OperatorVersionRuntimeBinding();
        binding.setImageDigest("sha256:image");

        NodeRunSnapshot snapshot = service.captureInput(nodeRun, flow,
            Map.of("config", Map.of("threshold", 3)),
            Map.of("records", List.of(Map.of("orderId", "A-1"))), Map.of(),
            version, definition,
            new RuntimeProfileService.RuntimeBindingView(binding, profile, List.of()));
        service.captureOutput(snapshot,
            Map.of("records", List.of(Map.of("orderId", "A-1", "matched", true))));

        assertEquals("COMPLETED", snapshot.getStatus());
        assertEquals(2, snapshot.getAttemptNo());
        assertEquals("sha256:image", snapshot.getRuntimeImageDigest());
        assertNotNull(snapshot.getOutputHash());
        verify(chunks, times(5)).insert(any(NodeRunSnapshotChunk.class));
    }
}
