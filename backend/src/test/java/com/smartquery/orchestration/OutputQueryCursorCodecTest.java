package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutputQueryCursorCodecTest {
    private final OutputQueryCursorCodec codec = new OutputQueryCursorCodec(new ObjectMapper());

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(codec, "secret", "test-output-cursor-secret-that-is-long-enough-123456");
        ReflectionTestUtils.setField(codec, "maxAgeSeconds", 3600L);
        codec.validateSecret();
    }

    @Test
    void signedCursorRoundTripsWithoutContainingBusinessSortValue() {
        String cursor = codec.encode(8L, 42, "query-hash");

        OutputQueryCursorCodec.CursorState decoded = codec.decode(cursor, 8L, "query-hash");

        assertEquals(42, decoded.rowIndex());
        assertEquals(8L, decoded.artifactId());
    }

    @Test
    void tamperingOrCrossQueryReuseIsRejected() {
        String cursor = codec.encode(8L, 42, "query-a");
        char replacement = cursor.charAt(cursor.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = cursor.substring(0, cursor.length() - 1) + replacement;

        assertThrows(BusinessException.class, () -> codec.decode(tampered, 8L, "query-a"));
        assertThrows(BusinessException.class, () -> codec.decode(cursor, 8L, "query-b"));
        assertThrows(BusinessException.class, () -> codec.decode(cursor, 9L, "query-a"));
    }
}
