package com.smartquery.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Database-backed catalog for immutable output target implementations. */
@Service
@RequiredArgsConstructor
public class OutputCapabilityRegistryService {
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9.-]{2,119}$");
    private static final Set<String> TYPES = Set.of("TRANSFORM", "PERSIST", "VIEW", "EXPORT", "ACTION");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ContentHashService contentHashService;
    private final ResourceAccessService resourceAccessService;

    public List<CapabilityView> list(boolean includeInactive) {
        if (includeInactive) {
            resourceAccessService.requirePermission(PermissionCodes.RUNTIME_MANAGE, "需要输出能力治理权限");
        }
        String sql = """
            SELECT c.id, c.code, c.name, c.capability_type, c.description, c.status,
                   c.required_permission, c.system_managed, c.created_by_user_id,
                   v.id AS version_id, v.version_no, v.status AS version_status,
                   v.content_hash, v.config_schema, v.input_schema, v.output_schema,
                   v.implementation_type, v.implementation_ref, v.artifact_sha256,
                   v.dependencies, v.runtime_type, v.interaction_events, v.security_policy,
                   v.created_by_user_id AS version_created_by, v.approved_by_user_id,
                   v.review_comment, v.reviewed_at
            FROM sq_output_capability c
            LEFT JOIN sq_output_capability_version v ON v.id = (
                SELECT MAX(v2.id) FROM sq_output_capability_version v2
                WHERE v2.capability_id = c.id AND v2.status = 'PUBLISHED'
            )
            """ + (includeInactive ? "" : " WHERE c.status = 'ENABLED' AND v.id IS NOT NULL")
            + " ORDER BY c.capability_type, c.code";
        List<CapabilityView> result = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) result.add(view(row));
        return List.copyOf(result);
    }

    public CapabilitySnapshot resolvePublished(String requestedCode) {
        String code = normalizeCode(requestedCode);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT c.id, c.code, c.name, c.capability_type, c.status, c.required_permission,
                   v.id AS version_id, v.version_no, v.status AS version_status,
                   v.content_hash, v.config_schema, v.input_schema, v.output_schema,
                   v.implementation_type, v.implementation_ref, v.artifact_sha256,
                   v.dependencies, v.runtime_type, v.interaction_events, v.security_policy
            FROM sq_output_capability c
            JOIN sq_output_capability_version v ON v.capability_id = c.id
            WHERE c.code = ? AND c.status = 'ENABLED' AND v.status = 'PUBLISHED'
            ORDER BY v.version_no DESC
            """, code);
        if (rows.isEmpty()) {
            throw new BusinessException(422, "输出能力未启用或没有已发布版本: " + code);
        }
        return snapshot(rows.get(0));
    }

    public List<CapabilityVersionView> versions(Long capabilityId) {
        requireManage();
        definition(capabilityId);
        List<CapabilityVersionView> result = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
            SELECT id, capability_id, version_no, status, content_hash, config_schema,
                   input_schema, output_schema, implementation_type, implementation_ref,
                   artifact_sha256, dependencies, runtime_type, interaction_events,
                   security_policy, created_by_user_id, approved_by_user_id,
                   review_comment, reviewed_at, created_at, source_code, test_report, build_log
            FROM sq_output_capability_version
            WHERE capability_id = ? ORDER BY version_no DESC
            """, capabilityId)) {
            result.add(new CapabilityVersionView(longValue(value(row, "id")), capabilityId,
                intValue(value(row, "version_no")), text(value(row, "status")),
                text(value(row, "content_hash")), object(value(row, "config_schema"), "configSchema"),
                object(value(row, "input_schema"), "inputSchema"), object(value(row, "output_schema"), "outputSchema"),
                text(value(row, "implementation_type")), text(value(row, "implementation_ref")),
                text(value(row, "artifact_sha256")), listValue(value(row, "dependencies")),
                text(value(row, "runtime_type")), listValue(value(row, "interaction_events")),
                object(value(row, "security_policy"), "securityPolicy"),
                text(value(row, "created_by_user_id")), text(value(row, "approved_by_user_id")),
                text(value(row, "review_comment")), value(row, "reviewed_at"), value(row, "created_at"),
                text(value(row, "source_code")), object(value(row, "test_report"), "testReport"),
                text(value(row, "build_log"))));
        }
        return List.copyOf(result);
    }

    /** Validates that an immutable target snapshot still points to the registered published artifact. */
    public CapabilitySnapshot requireRunnableSnapshot(Map<String, Object> target) {
        Long versionId = longValue(target.get("capabilityVersionId"));
        String code = normalizeCode(text(target.get("capabilityCode")));
        String expectedHash = text(target.get("contentHash"));
        if (versionId == null || expectedHash == null) {
            throw new BusinessException(422, "输出目标缺少已固化的能力版本或摘要: " + code);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT c.id, c.code, c.name, c.capability_type, c.status, c.required_permission,
                   v.id AS version_id, v.version_no, v.status AS version_status,
                   v.content_hash, v.config_schema, v.input_schema, v.output_schema,
                   v.implementation_type, v.implementation_ref, v.artifact_sha256,
                   v.dependencies, v.runtime_type, v.interaction_events, v.security_policy
            FROM sq_output_capability c
            JOIN sq_output_capability_version v ON v.capability_id = c.id
            WHERE v.id = ? AND c.code = ?
            """, versionId, code);
        if (rows.isEmpty()) throw new BusinessException(422, "输出能力版本不存在: " + versionId);
        CapabilitySnapshot snapshot = snapshot(rows.get(0));
        if (!"ENABLED".equals(snapshot.status()) || !"PUBLISHED".equals(snapshot.versionStatus())) {
            throw new BusinessException(422, "输出能力已停用: " + code);
        }
        if (!expectedHash.equals(snapshot.contentHash())) {
            throw new BusinessException(422, "输出能力版本摘要不一致: " + code);
        }
        if (snapshot.requiredPermission() != null) {
            resourceAccessService.requirePermission(snapshot.requiredPermission(), "无权执行输出能力: " + code);
        }
        Map<String, Object> config = map(target.get("config"));
        if ("REPLACE".equalsIgnoreCase(text(config.get("writeMode")))) {
            resourceAccessService.requirePermission(PermissionCodes.RUNTIME_MANAGE,
                "REPLACE覆盖写入需要运行治理权限或审批");
        }
        return snapshot;
    }

    @Transactional
    public Map<String, Object> createDefinition(Map<String, Object> body) {
        requireManage();
        String code = normalizeCode(required(body, "code"));
        String type = required(body, "capabilityType").toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new BusinessException(422, "capabilityType不受支持: " + type);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sq_output_capability WHERE code = ?", Integer.class, code);
        if (count != null && count > 0) throw new BusinessException(409, "输出能力编码已存在: " + code);
        jdbcTemplate.update("""
            INSERT INTO sq_output_capability
              (code, name, capability_type, description, status, required_permission,
               system_managed, created_by_user_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'DISABLED', ?, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, code, required(body, "name"), type, text(body.get("description")),
            text(body.get("requiredPermission")), currentUserId());
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM sq_output_capability WHERE code = ?", Long.class, code);
        return Map.of("id", id, "code", code, "status", "DISABLED");
    }

    @Transactional
    public Map<String, Object> createVersion(Long capabilityId, Map<String, Object> body) {
        requireManage();
        Map<String, Object> definition = definition(capabilityId);
        String implementationType = required(body, "implementationType").toUpperCase(Locale.ROOT);
        String implementationRef = required(body, "implementationRef");
        if (!implementationRef.matches("^(builtin|adapter|sandbox)://[^\\s]{1,980}$")) {
            throw new BusinessException(422, "implementationRef仅支持builtin://、adapter://或sandbox://受控引用");
        }
        String artifactSha256 = required(body, "artifactSha256").toLowerCase(Locale.ROOT);
        if (!artifactSha256.matches("^(sha256:)?[a-f0-9]{64}$")) {
            throw new BusinessException(422, "artifactSha256格式不正确");
        }
        String sourceCode = limitedText(body.get("sourceCode"), 200_000, "sourceCode");
        Map<String, Object> testReport = object(body.getOrDefault("testReport", Map.of()), "testReport");
        String buildLog = limitedText(body.get("buildLog"), 200_000, "buildLog");
        if ("CUSTOM_COMPONENT".equals(implementationType)
                && (sourceCode == null || testReport.isEmpty() || buildLog == null)) {
            throw new BusinessException(422, "自定义组件版本必须保存源码、沙箱测试报告和构建日志");
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("capabilityId", capabilityId);
        material.put("configSchema", object(body.getOrDefault("configSchema", Map.of()), "configSchema"));
        material.put("inputSchema", object(body.getOrDefault("inputSchema", Map.of()), "inputSchema"));
        material.put("outputSchema", object(body.getOrDefault("outputSchema", Map.of()), "outputSchema"));
        material.put("implementationType", implementationType);
        material.put("implementationRef", implementationRef);
        material.put("artifactSha256", artifactSha256);
        material.put("dependencies", list(body.getOrDefault("dependencies", List.of()), "dependencies"));
        material.put("runtimeType", required(body, "runtimeType"));
        material.put("interactionEvents", list(body.getOrDefault("interactionEvents", List.of()), "interactionEvents"));
        material.put("securityPolicy", object(body.getOrDefault("securityPolicy", Map.of()), "securityPolicy"));
        material.put("sourceCode", sourceCode == null ? "" : sourceCode);
        material.put("testReport", testReport);
        material.put("buildLog", buildLog == null ? "" : buildLog);
        String hash = contentHashService.sha256(material);
        Integer existing = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM sq_output_capability_version WHERE capability_id = ? AND content_hash = ?
            """, Integer.class, capabilityId, hash);
        if (existing != null && existing > 0) throw new BusinessException(409, "相同能力版本已经存在");
        Integer latest = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version_no), 0) FROM sq_output_capability_version WHERE capability_id = ?",
            Integer.class, capabilityId);
        jdbcTemplate.update("""
            INSERT INTO sq_output_capability_version
              (capability_id, version_no, status, content_hash, config_schema, input_schema,
               output_schema, implementation_type, implementation_ref, artifact_sha256,
               dependencies, runtime_type, interaction_events, security_policy,
               source_code, test_report, build_log, created_by_user_id, created_at)
            VALUES (?, ?, 'CANDIDATE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """, capabilityId, (latest == null ? 0 : latest) + 1, hash,
            json(material.get("configSchema")), json(material.get("inputSchema")),
            json(material.get("outputSchema")), implementationType, implementationRef,
            material.get("artifactSha256"), json(material.get("dependencies")), material.get("runtimeType"),
            json(material.get("interactionEvents")), json(material.get("securityPolicy")), sourceCode,
            json(testReport), buildLog, currentUserId());
        Long id = jdbcTemplate.queryForObject("""
            SELECT id FROM sq_output_capability_version WHERE capability_id = ? AND content_hash = ?
            """, Long.class, capabilityId, hash);
        return Map.of("id", id, "capabilityId", capabilityId, "capabilityCode", value(definition, "code"),
            "versionNo", (latest == null ? 0 : latest) + 1, "status", "CANDIDATE", "contentHash", hash);
    }

    @Transactional
    public Map<String, Object> reviewVersion(Long versionId, Map<String, Object> body) {
        requireManage();
        Map<String, Object> row = version(versionId);
        if (!"CANDIDATE".equals(String.valueOf(value(row, "status")))) {
            throw new BusinessException(409, "只有候选能力版本可以审批");
        }
        if (currentUserId().equals(String.valueOf(value(row, "created_by_user_id")))
                && !resourceAccessService.isAdmin()) {
            throw new BusinessException(403, "输出能力版本禁止自审");
        }
        String decision = required(body, "decision").toUpperCase(Locale.ROOT);
        String status = switch (decision) {
            case "APPROVE", "PUBLISH" -> "PUBLISHED";
            case "REJECT" -> "REJECTED";
            default -> throw new BusinessException(422, "decision仅支持APPROVE或REJECT");
        };
        jdbcTemplate.update("""
            UPDATE sq_output_capability_version
            SET status = ?, approved_by_user_id = ?, review_comment = ?, reviewed_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'CANDIDATE'
            """, status, currentUserId(), text(body.get("comment")), versionId);
        return Map.of("id", versionId, "status", status);
    }

    @Transactional
    public Map<String, Object> changeStatus(Long capabilityId, Map<String, Object> body) {
        requireManage();
        definition(capabilityId);
        String status = required(body, "status").toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw new BusinessException(422, "status仅支持ENABLED或DISABLED");
        if ("ENABLED".equals(status)) {
            Integer published = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sq_output_capability_version
                WHERE capability_id = ? AND status = 'PUBLISHED'
                """, Integer.class, capabilityId);
            if (published == null || published == 0) throw new BusinessException(422, "能力没有已发布版本，不能启用");
        }
        jdbcTemplate.update("""
            UPDATE sq_output_capability SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
            """, status, capabilityId);
        return Map.of("id", capabilityId, "status", status);
    }

    private CapabilityView view(Map<String, Object> row) {
        Long versionId = longValue(value(row, "version_id"));
        return new CapabilityView(longValue(value(row, "id")), text(value(row, "code")),
            text(value(row, "name")), text(value(row, "capability_type")), text(value(row, "description")),
            text(value(row, "status")), text(value(row, "required_permission")),
            intValue(value(row, "system_managed")) == 1, versionId,
            intValue(value(row, "version_no")), text(value(row, "version_status")),
            text(value(row, "content_hash")), object(value(row, "config_schema"), "configSchema"),
            text(value(row, "implementation_type")), text(value(row, "implementation_ref")),
            text(value(row, "artifact_sha256")), listValue(value(row, "dependencies")),
            text(value(row, "runtime_type")), listValue(value(row, "interaction_events")),
            object(value(row, "security_policy"), "securityPolicy"),
            text(value(row, "approved_by_user_id")), text(value(row, "review_comment")));
    }

    private CapabilitySnapshot snapshot(Map<String, Object> row) {
        return new CapabilitySnapshot(longValue(value(row, "id")), text(value(row, "code")),
            text(value(row, "name")), text(value(row, "capability_type")), text(value(row, "status")),
            text(value(row, "required_permission")), longValue(value(row, "version_id")),
            intValue(value(row, "version_no")), text(value(row, "version_status")),
            text(value(row, "content_hash")), object(value(row, "config_schema"), "configSchema"),
            object(value(row, "input_schema"), "inputSchema"), object(value(row, "output_schema"), "outputSchema"),
            text(value(row, "implementation_type")), text(value(row, "implementation_ref")),
            text(value(row, "artifact_sha256")), listValue(value(row, "dependencies")),
            text(value(row, "runtime_type")), listValue(value(row, "interaction_events")),
            object(value(row, "security_policy"), "securityPolicy"));
    }

    private Map<String, Object> definition(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM sq_output_capability WHERE id = ?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "输出能力不存在: " + id);
        return rows.get(0);
    }

    private Map<String, Object> version(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM sq_output_capability_version WHERE id = ?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "输出能力版本不存在: " + id);
        return rows.get(0);
    }

    private void requireManage() {
        resourceAccessService.requirePermission(PermissionCodes.RUNTIME_MANAGE, "需要输出能力治理权限");
    }

    private String normalizeCode(String code) {
        String value = code == null ? null : code.trim().toLowerCase(Locale.ROOT);
        if (value == null || !CODE.matcher(value).matches()) {
            throw new BusinessException(422, "输出能力编码格式不正确");
        }
        return value;
    }

    private String required(Map<String, Object> body, String field) {
        String value = text(body == null ? null : body.get(field));
        if (value == null) throw new BusinessException(422, field + "不能为空");
        return value;
    }

    private String limitedText(Object raw, int max, String name) {
        String value = text(raw);
        if (value != null && value.length() > max) throw new BusinessException(413, name + "超过长度限制");
        return value;
    }

    private Map<String, Object> object(Object raw, String name) {
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> map) return map(map);
        try { return objectMapper.readValue(String.valueOf(raw), new TypeReference<>() {}); }
        catch (Exception e) { throw new BusinessException(422, name + "必须是JSON对象"); }
    }

    private List<Object> list(Object raw, String name) {
        if (!(raw instanceof List<?> values)) throw new BusinessException(422, name + "必须是数组");
        return new ArrayList<>(values);
    }

    private List<Object> listValue(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> values) return new ArrayList<>(values);
        try { return objectMapper.readValue(String.valueOf(raw), new TypeReference<>() {}); }
        catch (Exception e) { throw new BusinessException(422, "能力目录中的数组字段损坏"); }
    }

    private Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> values) values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Object value(Map<String, Object> row, String key) {
        if (row.containsKey(key)) return row.get(key);
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
            .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private String text(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
    private Long longValue(Object raw) {
        if (raw == null) return null;
        return raw instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(raw));
    }
    private int intValue(Object raw) {
        if (raw == null) return 0;
        return raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
    }
    private String currentUserId() { return UserContextHolder.require().userId().toString(); }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException("输出能力版本序列化失败"); }
    }

    public record CapabilityView(Long id, String code, String name, String capabilityType,
                                 String description, String status, String requiredPermission,
                                 boolean systemManaged, Long versionId, int versionNo,
                                 String versionStatus, String contentHash, Map<String, Object> configSchema,
                                 String implementationType, String implementationRef,
                                 String artifactSha256, List<Object> dependencies, String runtimeType,
                                 List<Object> interactionEvents, Map<String, Object> securityPolicy,
                                 String approvedByUserId, String reviewComment) {}

    public record CapabilitySnapshot(Long capabilityId, String code, String name, String capabilityType,
                                     String status, String requiredPermission, Long versionId, int versionNo,
                                     String versionStatus, String contentHash, Map<String, Object> configSchema,
                                     Map<String, Object> inputSchema, Map<String, Object> outputSchema,
                                     String implementationType, String implementationRef,
                                     String artifactSha256, List<Object> dependencies, String runtimeType,
                                     List<Object> interactionEvents, Map<String, Object> securityPolicy) {}

    public record CapabilityVersionView(Long id, Long capabilityId, int versionNo, String status,
                                        String contentHash, Map<String, Object> configSchema,
                                        Map<String, Object> inputSchema, Map<String, Object> outputSchema,
                                        String implementationType, String implementationRef,
                                        String artifactSha256, List<Object> dependencies, String runtimeType,
                                        List<Object> interactionEvents, Map<String, Object> securityPolicy,
                                        String createdByUserId, String approvedByUserId, String reviewComment,
                                        Object reviewedAt, Object createdAt, String sourceCode,
                                        Map<String, Object> testReport, String buildLog) {}
}
