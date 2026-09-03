package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.NodeRun;
import com.smartquery.entity.NodeRunSnapshot;
import com.smartquery.entity.NodeRunSnapshotChunk;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.mapper.NodeRunSnapshotChunkMapper;
import com.smartquery.mapper.NodeRunSnapshotMapper;
import com.smartquery.orchestration.execution.OperatorExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stores exact replay material in attempt-scoped, portable TEXT chunks. */
@Service
@RequiredArgsConstructor
public class NodeRunSnapshotService {
    public static final String OPERATOR_VERSION = "OPERATOR_VERSION";
    public static final String NODE_CONFIG = "NODE_CONFIG";
    public static final String RUN_INPUT = "RUN_INPUT";
    public static final String UPSTREAM_OUTPUTS = "UPSTREAM_OUTPUTS";
    public static final String ORIGINAL_OUTPUT = "ORIGINAL_OUTPUT";
    private static final int CHUNK_CHARS = 48_000;

    private final NodeRunSnapshotMapper snapshotMapper;
    private final NodeRunSnapshotChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;
    private final ContentHashService contentHashService;

    @Value("${smart-query.orchestration.node.replay-snapshot-max-bytes:16777216}")
    private long maxSnapshotBytes;

    @Transactional
    public NodeRunSnapshot captureInput(NodeRun nodeRun, FlowVersion flowVersion,
                                        Map<String, Object> node,
                                        Map<String, Object> runInput,
                                        Map<String, OperatorExecutionResult> upstream,
                                        OperatorVersion version,
                                        OperatorDefinition definition,
                                        RuntimeProfileService.RuntimeBindingView runtime) {
        Map<String, Object> upstreamOutputs = new LinkedHashMap<>();
        upstream.forEach((key, value) -> upstreamOutputs.put(key, value.output()));
        Map<String, String> payloads = new LinkedHashMap<>();
        payloads.put(OPERATOR_VERSION, json(version));
        payloads.put(NODE_CONFIG, json(map(node.get("config"))));
        payloads.put(RUN_INPUT, json(runInput));
        payloads.put(UPSTREAM_OUTPUTS, json(upstreamOutputs));
        long bytes = payloads.values().stream().mapToLong(this::bytes).sum();
        if (bytes > maxSnapshotBytes) {
            throw new BusinessException(413, "节点回放输入快照超过" + maxSnapshotBytes + "字节限制");
        }

        NodeRunSnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<NodeRunSnapshot>()
            .eq(NodeRunSnapshot::getNodeRunId, nodeRun.getId()).last("LIMIT 1"));
        if (snapshot == null) {
            snapshot = new NodeRunSnapshot();
            snapshot.setNodeRunId(nodeRun.getId());
            snapshot.setRunId(nodeRun.getRunId());
            snapshot.setFlowVersionId(flowVersion.getId());
            snapshot.setNodeId(nodeRun.getNodeId());
            snapshot.setOperatorVersionId(version.getId());
            snapshot.setFlowContentHash(flowVersion.getContentHash());
            snapshot.setOperatorVersionContentHash(version.getContentHash());
            snapshot.setOperatorType(definition.getOperatorType());
            snapshot.setImplementationType(version.getImplementationType());
            snapshot.setRuntimeProfileId(runtime.profile().getId());
            snapshot.setRuntimeImageDigest(runtime.binding().getImageDigest());
            snapshot.setInputHash(nodeRun.getInputHash());
            snapshot.setAttemptNo(nodeRun.getAttemptNo());
            snapshot.setLeaseToken(nodeRun.getLeaseToken());
            snapshot.setStatus("INPUT_CAPTURED");
            snapshot.setSnapshotBytes(bytes);
            snapshotMapper.insert(snapshot);
        } else {
            verifyIdentity(snapshot, nodeRun, flowVersion, version);
            snapshotMapper.update(null, new LambdaUpdateWrapper<NodeRunSnapshot>()
                .eq(NodeRunSnapshot::getId, snapshot.getId())
                .set(NodeRunSnapshot::getFlowContentHash, flowVersion.getContentHash())
                .set(NodeRunSnapshot::getOperatorVersionContentHash, version.getContentHash())
                .set(NodeRunSnapshot::getOperatorType, definition.getOperatorType())
                .set(NodeRunSnapshot::getImplementationType, version.getImplementationType())
                .set(NodeRunSnapshot::getRuntimeProfileId, runtime.profile().getId())
                .set(NodeRunSnapshot::getRuntimeImageDigest, runtime.binding().getImageDigest())
                .set(NodeRunSnapshot::getInputHash, nodeRun.getInputHash())
                .set(NodeRunSnapshot::getOutputHash, null)
                .set(NodeRunSnapshot::getAttemptNo, nodeRun.getAttemptNo())
                .set(NodeRunSnapshot::getLeaseToken, nodeRun.getLeaseToken())
                .set(NodeRunSnapshot::getStatus, "INPUT_CAPTURED")
                .set(NodeRunSnapshot::getSnapshotBytes, bytes));
            snapshot = snapshotMapper.selectById(snapshot.getId());
        }
        for (Map.Entry<String, String> payload : payloads.entrySet()) {
            write(snapshot.getId(), snapshot.getAttemptNo(), payload.getKey(), payload.getValue());
        }
        return snapshot;
    }

    @Transactional
    public NodeRunSnapshot captureOutput(NodeRunSnapshot snapshot, Map<String, Object> output) {
        String payload = json(output);
        long outputBytes = bytes(payload);
        if (snapshot.getSnapshotBytes() + outputBytes > maxSnapshotBytes) {
            throw new BusinessException(413, "节点回放完整快照超过" + maxSnapshotBytes + "字节限制");
        }
        write(snapshot.getId(), snapshot.getAttemptNo(), ORIGINAL_OUTPUT, payload);
        String outputHash = contentHashService.sha256(output);
        int updated = snapshotMapper.update(null, new LambdaUpdateWrapper<NodeRunSnapshot>()
            .eq(NodeRunSnapshot::getId, snapshot.getId())
            .eq(NodeRunSnapshot::getAttemptNo, snapshot.getAttemptNo())
            .eq(NodeRunSnapshot::getLeaseToken, snapshot.getLeaseToken())
            .eq(NodeRunSnapshot::getStatus, "INPUT_CAPTURED")
            .set(NodeRunSnapshot::getOutputHash, outputHash)
            .set(NodeRunSnapshot::getSnapshotBytes, snapshot.getSnapshotBytes() + outputBytes)
            .set(NodeRunSnapshot::getStatus, "COMPLETED"));
        if (updated != 1) throw new BusinessException(409, "节点执行权已变化，拒绝写入过期回放快照");
        snapshot.setOutputHash(outputHash);
        snapshot.setSnapshotBytes(snapshot.getSnapshotBytes() + outputBytes);
        snapshot.setStatus("COMPLETED");
        return snapshot;
    }

    public NodeRunSnapshot require(Long id) {
        NodeRunSnapshot snapshot = id == null ? null : snapshotMapper.selectById(id);
        if (snapshot == null) throw new BusinessException(404, "节点回放快照不存在: " + id);
        return snapshot;
    }

    public NodeRunSnapshot requireByNodeRun(Long nodeRunId) {
        NodeRunSnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<NodeRunSnapshot>()
            .eq(NodeRunSnapshot::getNodeRunId, nodeRunId).last("LIMIT 1"));
        if (snapshot == null) throw new BusinessException(409, "该节点运行没有可回放快照");
        return snapshot;
    }

    public SnapshotMaterial material(NodeRunSnapshot snapshot) {
        return new SnapshotMaterial(
            object(read(snapshot, OPERATOR_VERSION)),
            object(read(snapshot, NODE_CONFIG)),
            object(read(snapshot, RUN_INPUT)),
            object(read(snapshot, UPSTREAM_OUTPUTS)),
            "COMPLETED".equals(snapshot.getStatus()) ? object(read(snapshot, ORIGINAL_OUTPUT)) : Map.of());
    }

    private void write(Long snapshotId, Integer attemptNo, String kind, String json) {
        chunkMapper.delete(new LambdaQueryWrapper<NodeRunSnapshotChunk>()
            .eq(NodeRunSnapshotChunk::getSnapshotId, snapshotId)
            .eq(NodeRunSnapshotChunk::getAttemptNo, attemptNo)
            .eq(NodeRunSnapshotChunk::getPayloadKind, kind));
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        int chunks = Math.max(1, (encoded.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
        for (int index = 0; index < chunks; index++) {
            NodeRunSnapshotChunk chunk = new NodeRunSnapshotChunk();
            chunk.setSnapshotId(snapshotId);
            chunk.setAttemptNo(attemptNo);
            chunk.setPayloadKind(kind);
            chunk.setChunkIndex(index);
            chunk.setPayloadText(encoded.substring(index * CHUNK_CHARS,
                Math.min(encoded.length(), (index + 1) * CHUNK_CHARS)));
            chunkMapper.insert(chunk);
        }
    }

    private String read(NodeRunSnapshot snapshot, String kind) {
        List<NodeRunSnapshotChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<NodeRunSnapshotChunk>()
                .eq(NodeRunSnapshotChunk::getSnapshotId, snapshot.getId())
                .eq(NodeRunSnapshotChunk::getAttemptNo, snapshot.getAttemptNo())
                .eq(NodeRunSnapshotChunk::getPayloadKind, kind)
                .orderByAsc(NodeRunSnapshotChunk::getChunkIndex));
        if (chunks.isEmpty()) throw new BusinessException(409, "节点回放快照缺少" + kind);
        StringBuilder encoded = new StringBuilder();
        chunks.forEach(chunk -> encoded.append(chunk.getPayloadText()));
        try { return new String(Base64.getDecoder().decode(encoded.toString()), StandardCharsets.UTF_8); }
        catch (Exception error) { throw new BusinessException(409, "节点回放快照" + kind + "损坏"); }
    }

    private void verifyIdentity(NodeRunSnapshot snapshot, NodeRun nodeRun,
                                FlowVersion flowVersion, OperatorVersion version) {
        if (!snapshot.getRunId().equals(nodeRun.getRunId())
                || !snapshot.getFlowVersionId().equals(flowVersion.getId())
                || !snapshot.getNodeId().equals(nodeRun.getNodeId())
                || !snapshot.getOperatorVersionId().equals(version.getId())) {
            throw new BusinessException(409, "节点运行的不可变回放身份发生变化");
        }
    }

    private Map<String, Object> object(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception error) { throw new BusinessException(409, "节点回放JSON快照损坏"); }
    }

    private Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private long bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new BusinessException(422, "节点回放快照无法序列化"); }
    }

    public record SnapshotMaterial(Map<String, Object> operatorVersion,
                                   Map<String, Object> nodeConfig,
                                   Map<String, Object> runInput,
                                   Map<String, Object> upstreamOutputs,
                                   Map<String, Object> originalOutput) {}
}
