package com.smartquery.orchestration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded retention worker; target row locks make concurrent application nodes safe. */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageRetentionScheduler {
    private final StorageGovernanceService governance;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${smart-query.orchestration.storage.retention-batch-size:25}")
    private int batchSize = 25;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeHistoricalUsage() {
        runCycle();
    }

    @Scheduled(fixedDelayString = "${smart-query.orchestration.storage.scan-interval-ms:3600000}")
    public void runCycle() {
        execute(false);
    }

    public void runNow() {
        execute(true);
    }

    private void execute(boolean forceArchive) {
        if (!running.compareAndSet(false, true)) return;
        try {
            int limit = Math.max(1, Math.min(batchSize, 100));
            int backfilled = governance.backfillHistoricalBatch(limit);
            if (!forceArchive && !Integer.valueOf(1).equals(governance.policy().getAutoArchiveEnabled())) return;
            int archived = 0;
            for (Long id : governance.dueOutputIds(limit)) {
                try {
                    governance.archiveOutputSystem(id, "超过输出结果保留期限");
                    archived++;
                } catch (Exception error) {
                    log.warn("[STORAGE_RETENTION] output {} archive failed: {}", id, root(error));
                }
            }
            for (Long id : governance.dueReplayIds(limit)) {
                try {
                    governance.archiveReplaySystem(id, "超过节点回放保留期限");
                    archived++;
                } catch (Exception error) {
                    log.warn("[STORAGE_RETENTION] replay {} archive failed: {}", id, root(error));
                }
            }
            if (backfilled > 0 || archived > 0) {
                log.info("[STORAGE_RETENTION] backfilled={}, archived={}", backfilled, archived);
            }
        } catch (Exception error) {
            log.warn("[STORAGE_RETENTION] scan failed: {}", root(error));
        } finally {
            running.set(false);
        }
    }

    private String root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
