package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.RuntimeBuildNonce;
import com.smartquery.mapper.RuntimeBuildNonceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

/** HMAC boundary for the unauthenticated-by-JWT external build worker endpoints. */
@Service
@RequiredArgsConstructor
public class RuntimeBuildWorkerAuthService {
    private final RuntimeBuildNonceMapper nonceMapper;

    @Value("${smart-query.runtime-builder.hmac-secret:}")
    private String secret;

    @Value("${smart-query.runtime-builder.signature-max-skew-seconds:300}")
    private long maxSkewSeconds;

    public boolean enabled() {
        return secret != null && !secret.isBlank() && secret.length() >= 32;
    }

    @Transactional
    public void verify(String method, String path, String body, String timestamp,
                       String nonce, String signature) {
        if (!enabled()) throw new BusinessException(503, "外部运行时构建器尚未配置");
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw new BusinessException(413, "构建器请求体超过1 MiB限制");
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(required(timestamp, "X-SQ-Build-Timestamp"));
        } catch (NumberFormatException error) {
            throw new BusinessException(401, "构建器时间戳格式不正确");
        }
        if (Math.abs(Instant.now().getEpochSecond() - epochSeconds) > maxSkewSeconds) {
            throw new BusinessException(401, "构建器请求已过期");
        }
        String safeNonce = required(nonce, "X-SQ-Build-Nonce");
        if (!safeNonce.matches("^[A-Za-z0-9_-]{16,128}$")) {
            throw new BusinessException(401, "构建器 nonce 格式不正确");
        }
        String provided = required(signature, "X-SQ-Build-Signature");
        if (provided.startsWith("sha256=")) provided = provided.substring(7);
        if (!provided.matches("^[a-fA-F0-9]{64}$")) {
            throw new BusinessException(401, "构建器签名格式不正确");
        }
        String canonical = timestamp + "\n" + safeNonce + "\n"
            + method.toUpperCase() + "\n" + path + "\n" + (body == null ? "" : body);
        String expected = hmac(canonical);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                provided.toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(401, "构建器签名校验失败");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        nonceMapper.delete(new LambdaQueryWrapper<RuntimeBuildNonce>()
            .lt(RuntimeBuildNonce::getExpiresAt, now));
        RuntimeBuildNonce used = new RuntimeBuildNonce();
        used.setNonceHash(sha256(safeNonce));
        used.setExpiresAt(now.plusSeconds(maxSkewSeconds * 2));
        try {
            nonceMapper.insert(used);
        } catch (DataIntegrityViolationException replay) {
            throw new BusinessException(409, "构建器请求 nonce 已使用");
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new BusinessException(500, "构建器签名服务不可用");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new BusinessException(500, "nonce 摘要失败");
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new BusinessException(401, field + "不能为空");
        return value.trim();
    }
}
