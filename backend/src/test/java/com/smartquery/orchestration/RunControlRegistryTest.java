package com.smartquery.orchestration;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RunControlRegistryTest {
    private final RunControlRegistry registry = new RunControlRegistry();

    @Test
    void timeoutIsVisibleAtEveryNodeBoundary() {
        RunControlRegistry.ActiveRun run = registry.register(claim(10L));
        RunControlRegistry.NodeControl node = run.node("slow-model");

        node.timeout();

        assertThrows(RunControlRegistry.NodeTimeoutException.class, node::assertActive);
    }

    @Test
    void leaseLossStopsTheWholeLocalRun() {
        RunControlRegistry.ActiveRun run = registry.register(claim(11L));

        run.stop(RunControlRegistry.StopReason.LEASE_LOST);

        assertThrows(RunControlRegistry.LeaseLostException.class, run::assertActive);
    }

    private RunLeaseService.LeaseClaim claim(Long runId) {
        return new RunLeaseService.LeaseClaim(runId, "instance", "token", 1, false,
            LocalDateTime.now());
    }
}
