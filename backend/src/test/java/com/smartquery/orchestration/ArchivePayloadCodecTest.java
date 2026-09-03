package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivePayloadCodecTest {
    private final ArchivePayloadCodec codec = new ArchivePayloadCodec(new ObjectMapper());

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(codec, "maxRestoreBytes", 10_000_000L);
    }

    @Test
    void compressedPayloadRoundTripsWithChecksum() {
        ArchivePayloadCodec.EncodedPayload encoded = codec.encode(Map.of(
            "rows", List.of(Map.of("orderId", "A-1", "memo", "重复支付".repeat(500)))));

        JsonNode restored = codec.decode(encoded.format(), encoded.originalBytes(),
            encoded.storedBytes(), encoded.checksum(), encoded.chunks());

        assertEquals("A-1", restored.path("rows").get(0).path("orderId").asText());
        assertTrue(encoded.storedBytes() < encoded.originalBytes());
    }

    @Test
    void checksumOrChunkTamperingIsRejected() {
        ArchivePayloadCodec.EncodedPayload encoded = codec.encode(Map.of("value", "sensitive"));
        assertThrows(BusinessException.class, () -> codec.decode(encoded.format(),
            encoded.originalBytes(), encoded.storedBytes(), "0".repeat(64), encoded.chunks()));
        assertThrows(BusinessException.class, () -> codec.decode(encoded.format(),
            encoded.originalBytes() + 1, encoded.storedBytes(), encoded.checksum(), encoded.chunks()));
    }
}
