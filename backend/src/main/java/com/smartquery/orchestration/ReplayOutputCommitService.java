package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.NodeReplay;
import com.smartquery.entity.NodeReplayChunk;
import com.smartquery.mapper.NodeReplayChunkMapper;
import com.smartquery.mapper.NodeReplayMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Atomically reserves quota, writes replay chunks and publishes replay success. */
@Service
@RequiredArgsConstructor
public class ReplayOutputCommitService {
    private static final String REPLAY_OUTPUT = "REPLAY_OUTPUT";
    private static final int CHUNK_CHARS = 48_000;
    private final NodeReplayMapper replayMapper;
    private final NodeReplayChunkMapper chunkMapper;
    private final StorageGovernanceService storageGovernance;

    @Transactional
    public void commit(NodeReplay replay, String outputJson, String outputHash,
                       String outputSummary, String diffSummary, String executionLog,
                       long executionTimeMs) {
        long payloadBytes = bytes(outputJson) + bytes(outputSummary) + bytes(diffSummary)
            + bytes(executionLog);
        storageGovernance.reserveHot(replay.getOwnerUserId(), StorageGovernanceService.REPLAY,
            Math.max(1, payloadBytes));
        write(replay, outputJson);
        UpdateWrapper<NodeReplay> update = new UpdateWrapper<>();
        update.set("status", RunStatus.SUCCESS)
            .set("output_hash", outputHash)
            .set("output_summary", outputSummary)
            .set("diff_summary", diffSummary)
            .set("execution_log", executionLog)
            .set("execution_time_ms", executionTimeMs)
            .set("finished_at", java.time.LocalDateTime.now())
            .set("lease_expires_at", null)
            .set("archive_status", StorageGovernanceService.ACTIVE)
            .set("payload_bytes", Math.max(1, payloadBytes))
            .set("usage_accounted", 1)
            .set("retention_until", storageGovernance.retentionUntil(StorageGovernanceService.REPLAY))
            .eq("id", replay.getId())
            .eq("status", RunStatus.RUNNING)
            .eq("attempt_no", replay.getAttemptNo())
            .eq("lease_token", replay.getLeaseToken());
        if (replayMapper.update(null, update) != 1) {
            throw new BusinessException(409, "节点回放执行权已变化，拒绝写入过期结果");
        }
    }

    private void write(NodeReplay replay, String outputJson) {
        String encoded = Base64.getEncoder().encodeToString(outputJson.getBytes(StandardCharsets.UTF_8));
        chunkMapper.delete(new LambdaQueryWrapper<NodeReplayChunk>()
            .eq(NodeReplayChunk::getReplayId, replay.getId())
            .eq(NodeReplayChunk::getAttemptNo, replay.getAttemptNo())
            .eq(NodeReplayChunk::getPayloadKind, REPLAY_OUTPUT));
        int chunks = Math.max(1, (encoded.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
        for (int index = 0; index < chunks; index++) {
            NodeReplayChunk chunk = new NodeReplayChunk();
            chunk.setReplayId(replay.getId());
            chunk.setAttemptNo(replay.getAttemptNo());
            chunk.setPayloadKind(REPLAY_OUTPUT);
            chunk.setChunkIndex(index);
            chunk.setPayloadText(encoded.substring(index * CHUNK_CHARS,
                Math.min(encoded.length(), (index + 1) * CHUNK_CHARS)));
            chunkMapper.insert(chunk);
        }
    }

    private long bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
