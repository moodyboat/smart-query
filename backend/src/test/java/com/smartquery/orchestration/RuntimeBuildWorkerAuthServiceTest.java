package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.mapper.RuntimeBuildNonceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class RuntimeBuildWorkerAuthServiceTest {
    private static final String SECRET = "test-runtime-builder-secret-that-is-long-enough";

    @Test
    void validHmacRequestIsAccepted() throws Exception {
        RuntimeBuildWorkerAuthService service = service(mock(RuntimeBuildNonceMapper.class));
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce_1234567890123456";
        String body = "{\"workerId\":\"ci-1\"}";
        String path = "/api/v2/runtime-build-worker/jobs/claim";

        service.verify("POST", path, body, timestamp, nonce,
            sign(timestamp + "\n" + nonce + "\nPOST\n" + path + "\n" + body));
    }

    @Test
    void persistedNonceRejectsReplay() throws Exception {
        RuntimeBuildNonceMapper nonces = mock(RuntimeBuildNonceMapper.class);
        doThrow(new DataIntegrityViolationException("duplicate")).when(nonces).insert(any());
        RuntimeBuildWorkerAuthService service = service(nonces);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce_1234567890123456";
        String body = "{}";
        String path = "/api/v2/runtime-build-worker/jobs/claim";

        BusinessException error = assertThrows(BusinessException.class, () -> service.verify(
            "POST", path, body, timestamp, nonce,
            sign(timestamp + "\n" + nonce + "\nPOST\n" + path + "\n" + body)));

        assertEquals(409, error.getCode());
    }

    @Test
    void emptyProductionSecretFailsClosed() {
        RuntimeBuildWorkerAuthService service = service(mock(RuntimeBuildNonceMapper.class));
        ReflectionTestUtils.setField(service, "secret", "");

        BusinessException error = assertThrows(BusinessException.class,
            () -> service.verify("POST", "/x", "{}", "1", "1234567890123456", "a".repeat(64)));

        assertEquals(503, error.getCode());
    }

    private RuntimeBuildWorkerAuthService service(RuntimeBuildNonceMapper nonces) {
        RuntimeBuildWorkerAuthService service = new RuntimeBuildWorkerAuthService(nonces);
        ReflectionTestUtils.setField(service, "secret", SECRET);
        ReflectionTestUtils.setField(service, "maxSkewSeconds", 300L);
        return service;
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
