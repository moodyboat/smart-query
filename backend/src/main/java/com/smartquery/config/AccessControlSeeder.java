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

/**
 * Creates the portable RBAC schema and imports initial catalog data.
 *
 * <p>The Java implementation is generic: role names, role codes and permission
 * assignments live in a data resource and are inserted only when absent. Runtime
 * authorization always reads the database.</p>
 */
@Slf4j
@Component
@Order(15)
@RequiredArgsConstructor
public class AccessControlSeeder implements CommandLineRunner {

    private static final String CATALOG_PATH = "catalog/access-control.json";

    private static final String CREATE_ROLE = """
        CREATE TABLE IF NOT EXISTS sq_role (
            id           BIGINT       NOT NULL AUTO_INCREMENT,
            code         VARCHAR(64)  NOT NULL,
            name         VARCHAR(80)  NOT NULL,
            description  VARCHAR(500),
            enabled      TINYINT      NOT NULL DEFAULT 1,
            system_role  TINYINT      NOT NULL DEFAULT 0,
            default_role TINYINT      NOT NULL DEFAULT 0,
            sort_order   INT          NOT NULL DEFAULT 100,
            created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted      TINYINT      NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            CONSTRAINT uk_role_code UNIQUE (code)
        )
        """;

    private static final String CREATE_PERMISSION = """
        CREATE TABLE IF NOT EXISTS sq_permission (
            id          BIGINT       NOT NULL AUTO_INCREMENT,
            code        VARCHAR(100) NOT NULL,
            name        VARCHAR(100) NOT NULL,
            description VARCHAR(500),
            module_name VARCHAR(80)  NOT NULL,
            enabled     TINYINT      NOT NULL DEFAULT 1,
            sort_order  INT          NOT NULL DEFAULT 100,
            created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted     TINYINT      NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            CONSTRAINT uk_permission_code UNIQUE (code)
        )
        """;

    private static final String CREATE_ROLE_PERMISSION = """
        CREATE TABLE IF NOT EXISTS sq_role_permission (
            id            BIGINT   NOT NULL AUTO_INCREMENT,
            role_id       BIGINT   NOT NULL,
            permission_id BIGINT   NOT NULL,
            created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id)
        )
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        jdbcTemplate.execute(CREATE_ROLE);
        jdbcTemplate.execute(CREATE_PERMISSION);
        jdbcTemplate.execute(CREATE_ROLE_PERMISSION);
        createIndex("CREATE INDEX idx_role_enabled ON sq_role(enabled, deleted, sort_order)");
        createIndex("CREATE INDEX idx_role_permission_role ON sq_role_permission(role_id)");
        createIndex("CREATE INDEX idx_role_permission_permission ON sq_role_permission(permission_id)");
        importCatalog();
        try {
            importLegacyRoleCodes();
        } catch (Exception e) {
            log.debug("[RBAC] sq_user 尚未创建，跳过历史角色迁移: {}", e.getMessage());
        }
        log.info("[RBAC] database-backed role and permission catalog ready");
    }

    private void importCatalog() throws Exception {
        ClassPathResource resource = new ClassPathResource(CATALOG_PATH);
        JsonNode root;
        try (var input = resource.getInputStream()) {
            root = objectMapper.readTree(input);
        }
        for (JsonNode permission : root.path("permissions")) {
            String code = permission.path("code").asText();
            if (exists("sq_permission", code)) continue;
            jdbcTemplate.update("""
                INSERT INTO sq_permission
                  (code, name, description, module_name, enabled, sort_order,
                   created_at, updated_at, deleted)
                VALUES (?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, code, permission.path("name").asText(), nullable(permission, "description"),
                permission.path("module").asText("平台"), permission.path("sortOrder").asInt(100));
        }
        for (JsonNode role : root.path("roles")) {
            String code = role.path("code").asText();
            if (exists("sq_role", code)) continue;
            if (role.path("defaultRole").asBoolean(false)) {
                jdbcTemplate.update("UPDATE sq_role SET default_role = 0 WHERE deleted = 0");
            }
            jdbcTemplate.update("""
                INSERT INTO sq_role
                  (code, name, description, enabled, system_role, default_role, sort_order,
                   created_at, updated_at, deleted)
                VALUES (?, ?, ?, 1, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, code, role.path("name").asText(), nullable(role, "description"),
                role.path("systemRole").asBoolean(true) ? 1 : 0,
                role.path("defaultRole").asBoolean(false) ? 1 : 0,
                role.path("sortOrder").asInt(100));
            Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM sq_role WHERE code = ? AND deleted = 0", Long.class, code);
            for (JsonNode permissionCode : role.path("permissions")) {
                Long permissionId = jdbcTemplate.queryForObject(
                    "SELECT id FROM sq_permission WHERE code = ? AND deleted = 0",
                    Long.class, permissionCode.asText());
                jdbcTemplate.update("""
                    INSERT INTO sq_role_permission (role_id, permission_id, created_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """, roleId, permissionId);
            }
        }
    }

    /** Preserve installations that already contain custom role codes in sq_user. */
    private void importLegacyRoleCodes() {
        for (String code : jdbcTemplate.query(
                "SELECT DISTINCT role FROM sq_user WHERE role IS NOT NULL AND deleted = 0",
                (rs, rowNum) -> rs.getString(1))) {
            if (code == null || code.isBlank() || exists("sq_role", code)) continue;
            jdbcTemplate.update("""
                INSERT INTO sq_role
                  (code, name, description, enabled, system_role, default_role, sort_order,
                   created_at, updated_at, deleted)
                VALUES (?, ?, '由历史用户数据自动迁移，请在平台配置中补充权限', 1, 0, 0, 900,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, code, code);
        }
    }

    private boolean exists(String table, String code) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE code = ? AND deleted = 0", Integer.class, code);
        return count != null && count > 0;
    }

    private String nullable(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private void createIndex(String ddl) {
        try {
            jdbcTemplate.execute(ddl);
        } catch (Exception ignored) {
            // MySQL and DM8 report duplicate indexes differently.
        }
    }
}
