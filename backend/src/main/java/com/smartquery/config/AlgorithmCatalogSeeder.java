package com.smartquery.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Imports the versioned algorithm data catalog into sq_algorithm. */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class AlgorithmCatalogSeeder implements CommandLineRunner {

    private static final String CATALOG_PATH = "catalog/builtin-algorithms.json";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        ensureSchema();
        // Preserve the implementation that legacy models were created with before a
        // newer built-in catalog is allowed to replace the source definition.
        backfillLegacyModelSnapshots();
        JsonNode root;
        try (var input = new ClassPathResource(CATALOG_PATH).getInputStream()) {
            root = objectMapper.readTree(input);
        }
        validateCatalog(root);
        int version = root.path("version").asInt();
        Integer applied = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM sq_catalog_import
            WHERE catalog_key = 'builtin-algorithms' AND version_no = ?
            """, Integer.class, version);
        if (applied != null && applied > 0) return;

        int imported = 0;
        for (JsonNode algorithm : root.path("algorithms")) {
            String id = algorithm.path("algorithmId").asText();
            String modelTypes = objectMapper.writeValueAsString(algorithm.path("modelTypes"));
            String params = objectMapper.writeValueAsString(algorithm.path("paramsSchema"));
            String aliases = objectMapper.writeValueAsString(algorithm.path("aliases"));
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sq_algorithm WHERE algorithm_id = ?", Integer.class, id);
            if (count == null || count == 0) {
                jdbcTemplate.update("""
                    INSERT INTO sq_algorithm
                      (algorithm_id, name, description, model_types, params_schema,
                       python_code_template, aliases, is_builtin, enabled, version_no, icon, category,
                       created_at, updated_at, deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                    """, id, algorithm.path("name").asText(), nullable(algorithm, "description"),
                    modelTypes, params, algorithm.path("pythonCodeTemplate").asText(), aliases,
                    version, nullable(algorithm, "icon"), nullable(algorithm, "category"));
            } else {
                // Built-ins are immutable through the API, so the versioned catalog remains
                // their single source of truth. Custom database rows are never touched here.
                jdbcTemplate.update("""
                    UPDATE sq_algorithm
                    SET name = ?, description = ?, model_types = ?, params_schema = ?,
                        python_code_template = ?, aliases = ?, icon = ?, category = ?,
                        version_no = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE algorithm_id = ? AND is_builtin = 1
                    """, algorithm.path("name").asText(), nullable(algorithm, "description"),
                    modelTypes, params, algorithm.path("pythonCodeTemplate").asText(), aliases,
                    nullable(algorithm, "icon"), nullable(algorithm, "category"), version, id);
            }
            imported++;
        }
        jdbcTemplate.update("""
            INSERT INTO sq_catalog_import (catalog_key, version_no, imported_at)
            VALUES ('builtin-algorithms', ?, CURRENT_TIMESTAMP)
            """, version);
        log.info("[ALGORITHM-CATALOG] imported version {} with {} entries", version, imported);
    }

    private void validateCatalog(JsonNode root) {
        if (root.path("version").asInt(0) < 1) {
            throw new IllegalStateException("算法目录 version 必须大于 0");
        }
        if (!root.path("algorithms").isArray() || root.path("algorithms").isEmpty()) {
            throw new IllegalStateException("算法目录不能为空");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode algorithm : root.path("algorithms")) {
            String id = algorithm.path("algorithmId").asText();
            if (id.isBlank() || !ids.add(id)) {
                throw new IllegalStateException("算法目录存在空标识或重复标识: " + id);
            }
            if (algorithm.path("name").asText().isBlank()
                    || !algorithm.path("modelTypes").isArray()
                    || algorithm.path("modelTypes").isEmpty()
                    || !algorithm.path("paramsSchema").isArray()
                    || algorithm.path("pythonCodeTemplate").asText().isBlank()) {
                throw new IllegalStateException("算法目录条目不完整: " + id);
            }
        }
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS sq_algorithm (
                id BIGINT NOT NULL AUTO_INCREMENT,
                algorithm_id VARCHAR(100) NOT NULL,
                name VARCHAR(200) NOT NULL,
                description TEXT,
                model_types TEXT NOT NULL,
                params_schema TEXT NOT NULL,
                python_code_template TEXT NOT NULL,
                aliases TEXT,
                is_builtin TINYINT NOT NULL DEFAULT 0,
                enabled TINYINT NOT NULL DEFAULT 1,
                version_no INT NOT NULL DEFAULT 1,
                icon VARCHAR(20),
                category VARCHAR(50),
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                deleted TINYINT NOT NULL DEFAULT 0,
                PRIMARY KEY (id),
                CONSTRAINT uk_algorithm_id UNIQUE (algorithm_id)
            )
            """);
        try {
            jdbcTemplate.execute("ALTER TABLE sq_algorithm ADD COLUMN aliases TEXT");
        } catch (Exception ignored) {
            // Column already exists.
        }
        try {
            jdbcTemplate.execute("ALTER TABLE sq_algorithm ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1");
        } catch (Exception ignored) {
            // Column already exists.
        }
        try {
            jdbcTemplate.execute("ALTER TABLE sq_algorithm ADD COLUMN version_no INT NOT NULL DEFAULT 1");
        } catch (Exception ignored) {
            // Column already exists.
        }
        jdbcTemplate.update("UPDATE sq_algorithm SET enabled = 1 WHERE enabled IS NULL");
        jdbcTemplate.update("UPDATE sq_algorithm SET version_no = 1 WHERE version_no IS NULL");
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS sq_catalog_import (
                id BIGINT NOT NULL AUTO_INCREMENT,
                catalog_key VARCHAR(100) NOT NULL,
                version_no INT NOT NULL,
                imported_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                CONSTRAINT uk_catalog_import UNIQUE (catalog_key, version_no)
            )
            """);
    }

    private void backfillLegacyModelSnapshots() {
        List<StoredAlgorithm> algorithms = jdbcTemplate.query("""
            SELECT algorithm_id, name, model_types, params_schema, python_code_template,
                   aliases, version_no
            FROM sq_algorithm WHERE deleted = 0
            """, (rs, rowNum) -> storedAlgorithm(rs));
        if (algorithms.isEmpty()) return;

        List<LegacyModel> models = jdbcTemplate.query("""
            SELECT id, algorithm FROM sq_mining_model
            WHERE deleted = 0 AND (algorithm_snapshot IS NULL OR algorithm_snapshot = '')
            """, (rs, rowNum) -> new LegacyModel(rs.getLong("id"), rs.getString("algorithm")));
        int backfilled = 0;
        for (LegacyModel model : models) {
            StoredAlgorithm algorithm = algorithms.stream()
                .filter(candidate -> candidate.identities().contains(normalize(model.algorithmId())))
                .findFirst().orElse(null);
            if (algorithm == null) {
                log.warn("[ALGORITHM-CATALOG] legacy model {} references unknown algorithm {}",
                    model.id(), model.algorithmId());
                continue;
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("algorithmId", algorithm.algorithmId());
            snapshot.put("name", algorithm.name());
            snapshot.put("versionNo", algorithm.versionNo());
            snapshot.put("modelTypes", parseJson(algorithm.modelTypes()));
            snapshot.put("paramsSchema", parseJson(algorithm.paramsSchema()));
            snapshot.put("pythonCodeTemplate", algorithm.pythonCodeTemplate());
            snapshot.put("codeSha256", sha256(algorithm.pythonCodeTemplate()));
            try {
                int updated = jdbcTemplate.update("""
                    UPDATE sq_mining_model
                    SET algorithm = ?, algorithm_version = ?, algorithm_snapshot = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND (algorithm_snapshot IS NULL OR algorithm_snapshot = '')
                    """, algorithm.algorithmId(), algorithm.versionNo(),
                    objectMapper.writeValueAsString(snapshot), model.id());
                backfilled += updated;
            } catch (Exception e) {
                throw new IllegalStateException("旧模型算法快照固化失败: " + model.id(), e);
            }
        }
        if (backfilled > 0) {
            log.info("[ALGORITHM-CATALOG] backfilled immutable snapshots for {} legacy models", backfilled);
        }
    }

    private StoredAlgorithm storedAlgorithm(ResultSet rs) throws java.sql.SQLException {
        int version = rs.getInt("version_no");
        if (rs.wasNull() || version < 1) version = 1;
        Set<String> identities = new LinkedHashSet<>();
        identities.add(normalize(rs.getString("algorithm_id")));
        String aliasesJson = rs.getString("aliases");
        if (aliasesJson != null && !aliasesJson.isBlank()) {
            try {
                for (JsonNode alias : objectMapper.readTree(aliasesJson)) {
                    identities.add(normalize(alias.asText()));
                }
            } catch (Exception e) {
                throw new IllegalStateException("算法别名配置无效: " + rs.getString("algorithm_id"), e);
            }
        }
        return new StoredAlgorithm(
            rs.getString("algorithm_id"), rs.getString("name"),
            rs.getString("model_types"), rs.getString("params_schema"),
            rs.getString("python_code_template"), version, identities);
    }

    private Object parseJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("算法目录 JSON 配置无效", e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record StoredAlgorithm(String algorithmId, String name, String modelTypes,
                                   String paramsSchema, String pythonCodeTemplate,
                                   int versionNo, Set<String> identities) {}

    private record LegacyModel(long id, String algorithmId) {}

    private String nullable(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value == null || value.isBlank() ? null : value;
    }
}
