package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.orchestration.execution.LineageSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class OutputArtifactIndexServiceTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final OutputArtifactIndexService service = new OutputArtifactIndexService(
        jdbcTemplate, new ContentHashService(new ObjectMapper()));

    @Test
    void indexesMergedDisplayScalarsWithStableTypesAndSkipsArrays() {
        ReflectionTestUtils.setField(service, "maxIndexedFieldsPerRow", 50);
        List<Object[]> inserted = new ArrayList<>();
        doAnswer(invocation -> {
            List<Object[]> batch = invocation.getArgument(1);
            batch.forEach(values -> inserted.add(values.clone()));
            return new int[batch.size()];
        }).when(jdbcTemplate).batchUpdate(anyString(), anyList());
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put(LineageSupport.SOURCE_SNAPSHOTS, List.of(Map.of(
            "orderId", "A-1", "amount", 90, "address", Map.of("city", "上海"))));
        record.put("amount", 100.5);
        record.put("predicted", true);
        record.put("huge", new BigInteger("1234567890123456789012345678901234567890"));
        record.put("labels", List.of("overdue", "high-risk"));

        service.index(7L, List.of(record));

        Map<String, Object[]> byField = inserted.stream().collect(java.util.stream.Collectors.toMap(
            values -> String.valueOf(values[2]), values -> values));
        assertEquals(5, byField.size());
        assertEquals("NUMBER", byField.get("amount")[3]);
        assertEquals("100.5", byField.get("amount")[4]);
        assertEquals("BOOLEAN", byField.get("predicted")[3]);
        assertEquals("STRING", byField.get("huge")[3]);
        assertEquals(new ContentHashService(new ObjectMapper()).sha256(
            "1234567890123456789012345678901234567890"), byField.get("huge")[8]);
        assertEquals("上海", byField.get("address.city")[4]);
        assertFalse(byField.containsKey("labels"));
    }
}
