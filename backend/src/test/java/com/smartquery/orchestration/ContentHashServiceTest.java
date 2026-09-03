package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContentHashServiceTest {

    private final ContentHashService service = new ContentHashService(new ObjectMapper());

    @Test
    void objectKeyOrderDoesNotChangeHashButArrayOrderDoes() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "rule");
        first.put("config", Map.of("threshold", 3, "window", "30d"));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("config", Map.of("window", "30d", "threshold", 3));
        second.put("name", "rule");

        assertEquals(service.sha256(first), service.sha256(second));
        assertNotEquals(service.sha256(List.of("filter", "aggregate")),
            service.sha256(List.of("aggregate", "filter")));
    }
}
