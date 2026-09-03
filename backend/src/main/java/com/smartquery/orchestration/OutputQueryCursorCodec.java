package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** Signed opaque keyset cursor; it contains no business field value. */
@Component
public class OutputQueryCursorCodec {
    private final ObjectMapper objectMapper;

    @Value("${smart-query.orchestration.output-query.cursor-secret}")
    private String secret;

    @Value("${smart-query.orchestration.output-query.cursor-max-age-seconds:86400}")
    private long maxAgeSeconds = 86_400L;

    public OutputQueryCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("输出查询游标密钥必须至少32字节");
        }
    }

    public String encode(Long artifactId, int rowIndex, String queryHash) {
        try {
            CursorState state = new CursorState(artifactId, rowIndex, queryHash,
                Instant.now().getEpochSecond());
            byte[] payload = objectMapper.writeValueAsBytes(state);
            return url(payload) + "." + url(hmac(payload));
        } catch (Exception error) {
            throw new BusinessException(500, "无法生成结果查询游标");
        }
    }

    public CursorState decode(String cursor, Long artifactId, String queryHash) {
        try {
            String[] parts = cursor == null ? new String[0] : cursor.split("\\.", -1);
            if (parts.length != 2) throw invalid();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(signature, hmac(payload))) throw invalid();
            CursorState state = objectMapper.readValue(payload, CursorState.class);
            long age = Instant.now().getEpochSecond() - state.issuedAt();
            if (!artifactId.equals(state.artifactId()) || !queryHash.equals(state.queryHash())
                    || age < 0 || age > Math.max(60, maxAgeSeconds)) {
                throw invalid();
            }
            return state;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw invalid();
        }
    }

    private byte[] hmac(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(payload);
    }

    private String url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private BusinessException invalid() {
        return new BusinessException(400, "结果查询游标无效、已过期或不属于当前查询");
    }

    public record CursorState(Long artifactId, int rowIndex, String queryHash, long issuedAt) {}
}
