package com.smartquery.orchestration;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Local interrupt handles complement database fencing for prompt cancellation and timeouts. */
@Component
public class RunControlRegistry {
    private final ConcurrentMap<Long, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    public ActiveRun register(RunLeaseService.LeaseClaim claim) {
        ActiveRun control = new ActiveRun(claim);
        ActiveRun existing = activeRuns.putIfAbsent(claim.runId(), control);
        return existing == null ? control : existing;
    }

    public void unregister(ActiveRun control) {
        activeRuns.remove(control.claim().runId(), control);
    }

    public Optional<ActiveRun> find(Long runId) {
        return Optional.ofNullable(activeRuns.get(runId));
    }

    public List<ActiveRun> activeRuns() {
        return List.copyOf(activeRuns.values());
    }

    public void cancelLocal(Long runId) {
        find(runId).ifPresent(run -> run.stop(StopReason.CANCELED));
    }

    public enum StopReason { CANCELED, LEASE_LOST, ABORTED }

    public static final class ActiveRun {
        private final RunLeaseService.LeaseClaim claim;
        private final AtomicReference<StopReason> stopReason = new AtomicReference<>();
        private final ConcurrentMap<String, NodeControl> nodes = new ConcurrentHashMap<>();

        private ActiveRun(RunLeaseService.LeaseClaim claim) {
            this.claim = claim;
        }

        public RunLeaseService.LeaseClaim claim() { return claim; }

        public NodeControl node(String nodeId) {
            NodeControl node = new NodeControl(this, nodeId);
            NodeControl existing = nodes.putIfAbsent(nodeId, node);
            return existing == null ? node : existing;
        }

        public void removeNode(NodeControl node) {
            nodes.remove(node.nodeId(), node);
        }

        public void stop(StopReason reason) {
            stopReason.compareAndSet(null, reason);
            nodes.values().forEach(NodeControl::interrupt);
        }

        public boolean stopped() { return stopReason.get() != null; }

        public StopReason stopReason() { return stopReason.get(); }

        public void assertActive() {
            StopReason reason = stopReason.get();
            if (reason == StopReason.CANCELED) throw new RunCanceledException();
            if (reason == StopReason.LEASE_LOST) throw new LeaseLostException();
            if (reason == StopReason.ABORTED) throw new RunAbortedException();
        }
    }

    public static final class NodeControl {
        private final ActiveRun run;
        private final String nodeId;
        private final AtomicReference<Thread> runner = new AtomicReference<>();
        private final AtomicBoolean timedOut = new AtomicBoolean();

        private NodeControl(ActiveRun run, String nodeId) {
            this.run = run;
            this.nodeId = nodeId;
        }

        public String nodeId() { return nodeId; }

        public void bindCurrentThread() {
            runner.set(Thread.currentThread());
            assertActive();
        }

        public void timeout() {
            timedOut.set(true);
            interrupt();
        }

        public boolean timedOut() { return timedOut.get(); }

        public void interrupt() {
            Thread thread = runner.get();
            if (thread != null) thread.interrupt();
        }

        public void assertActive() {
            if (timedOut.get()) throw new NodeTimeoutException(nodeId);
            run.assertActive();
        }
    }

    public static class RunCanceledException extends RuntimeException {
        public RunCanceledException() { super("运行已取消"); }
    }

    public static class LeaseLostException extends RuntimeException {
        public LeaseLostException() { super("运行租约已失效，当前实例停止执行"); }
    }

    public static class RunAbortedException extends RuntimeException {
        public RunAbortedException() { super("同层节点失败，执行已中止"); }
    }

    public static class NodeTimeoutException extends RuntimeException {
        public NodeTimeoutException(String nodeId) { super("节点[" + nodeId + "]执行超时"); }
    }
}
