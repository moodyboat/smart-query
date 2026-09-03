package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LineageSupportTest {

    @Test
    void enrichesOriginalRecordAndRejectsDroppedLineage() {
        List<Map<String, Object>> enriched = LineageSupport.enrich(9L,
            List.of(Map.of("id", "A", "amount", 10)));

        assertEquals(List.of("run:9:record:1"), enriched.get(0).get(LineageSupport.SOURCE_REFS));
        LineageSupport.requirePreserved(Map.of("records", enriched), "model");
        assertThrows(BusinessException.class, () -> LineageSupport.requirePreserved(
            Map.of("records", List.of(Map.of("score", 0.9))), "model"));
    }
}
