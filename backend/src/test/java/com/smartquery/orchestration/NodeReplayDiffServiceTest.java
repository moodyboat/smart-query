package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeReplayDiffServiceTest {
    private final ContentHashService hashes = new ContentHashService(new ObjectMapper());
    private final NodeReplayDiffService service = new NodeReplayDiffService(hashes);

    @Test
    void identicalOutputsAreReportedAsExact() {
        Map<String, Object> output = Map.of("records", List.of(
            Map.of("orderId", "A-1", "amount", 100, "__sourceRefs", List.of("order:A-1"))),
            "recordCount", 1);

        Map<String, Object> diff = service.compare(output, output, 10);

        assertTrue((Boolean) diff.get("exactMatch"));
        assertEquals(0, diff.get("added"));
        assertEquals(0, diff.get("removed"));
        assertEquals(0, diff.get("changed"));
    }

    @Test
    void provenanceIdentityDetectsAddedRemovedAndChangedRecords() {
        Map<String, Object> original = Map.of("records", List.of(
            Map.of("orderId", "A", "amount", 100, "__sourceRefs", List.of("order:A")),
            Map.of("orderId", "B", "amount", 200, "__sourceRefs", List.of("order:B"))));
        Map<String, Object> replay = Map.of("records", List.of(
            Map.of("orderId", "A", "amount", 110, "__sourceRefs", List.of("order:A")),
            Map.of("orderId", "C", "amount", 300, "__sourceRefs", List.of("order:C"))));

        Map<String, Object> diff = service.compare(original, replay, 10);

        assertFalse((Boolean) diff.get("exactMatch"));
        assertEquals(1, diff.get("added"));
        assertEquals(1, diff.get("removed"));
        assertEquals(1, diff.get("changed"));
        assertEquals(3, ((List<?>) diff.get("samples")).size());
    }
}
