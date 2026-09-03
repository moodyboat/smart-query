package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Portable compressed archive codec with size and checksum verification. */
@Component
public class ArchivePayloadCodec {
    public static final String FORMAT = "GZIP_BASE64_JSON_V1";
    private static final int CHUNK_CHARS = 48_000;
    private final ObjectMapper objectMapper;

    @Value("${smart-query.orchestration.storage.max-restore-bytes:536870912}")
    private long maxRestoreBytes = 536_870_912L;

    public ArchivePayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EncodedPayload encode(Object value) {
        try {
            byte[] original = objectMapper.writeValueAsBytes(value);
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(original);
            }
            byte[] stored = compressed.toByteArray();
            String encoded = Base64.getEncoder().encodeToString(stored);
            List<String> chunks = new ArrayList<>();
            for (int start = 0; start < encoded.length(); start += CHUNK_CHARS) {
                chunks.add(encoded.substring(start, Math.min(encoded.length(), start + CHUNK_CHARS)));
            }
            if (chunks.isEmpty()) chunks.add("");
            return new EncodedPayload(FORMAT, original.length,
                encoded.getBytes(StandardCharsets.US_ASCII).length,
                sha256(original), List.copyOf(chunks));
        } catch (Exception error) {
            throw new BusinessException(500, "无法压缩归档明细");
        }
    }

    public JsonNode decode(String format, long expectedBytes, long expectedStoredBytes,
                           String expectedChecksum, List<String> chunks) {
        if (!FORMAT.equals(format) || chunks == null || chunks.isEmpty()
                || expectedBytes < 0 || expectedBytes > Math.max(1, maxRestoreBytes)
                || expectedStoredBytes < 0 || expectedStoredBytes > Math.max(1, maxRestoreBytes) * 2) {
            throw corrupt();
        }
        try {
            StringBuilder encoded = new StringBuilder();
            for (String chunk : chunks) {
                if (chunk == null || encoded.length() + (long) chunk.length() > expectedStoredBytes) throw corrupt();
                encoded.append(chunk);
            }
            if (encoded.length() != expectedStoredBytes) throw corrupt();
            byte[] compressed = Base64.getDecoder().decode(encoded.toString());
            ByteArrayOutputStream original = new ByteArrayOutputStream(
                (int) Math.min(expectedBytes, 1_048_576L));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                byte[] buffer = new byte[8192];
                int read;
                long total = 0;
                while ((read = gzip.read(buffer)) != -1) {
                    if (read == 0) continue;
                    total += read;
                    if (total > expectedBytes || total > Math.max(1, maxRestoreBytes)) throw corrupt();
                    original.write(buffer, 0, read);
                }
            }
            byte[] value = original.toByteArray();
            if (value.length != expectedBytes || !MessageDigest.isEqual(
                    sha256(value).getBytes(StandardCharsets.US_ASCII),
                    String.valueOf(expectedChecksum).getBytes(StandardCharsets.US_ASCII))) {
                throw corrupt();
            }
            return objectMapper.readTree(value);
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw corrupt();
        }
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private BusinessException corrupt() {
        return new BusinessException(409, "归档明细格式、大小或完整性校验失败");
    }

    public record EncodedPayload(String format, long originalBytes, long storedBytes,
                                 String checksum, List<String> chunks) {}
}
