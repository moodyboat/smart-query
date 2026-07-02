package com.smartquery.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.entity.RoleScenario;
import com.smartquery.entity.Scenario;
import com.smartquery.entity.User;
import com.smartquery.mapper.RoleScenarioMapper;
import com.smartquery.mapper.ScenarioMapper;
import com.smartquery.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 首次启动时注入默认管理员账号（密码 BCrypt 加密）。
 * 项目已移除 Flyway，schema 由 smart_query_seed.sql dump 导入；
 * 此处用 CREATE TABLE IF NOT EXISTS / ALTER TABLE ADD COLUMN IF NOT EXISTS 兜底，
 * 保证表/列一定存在（兼容本地老库与 Docker 全新库）。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    // DDL 兼容 MySQL 与 DM8（COMPATIBLE_MODE=4）：
    //   - 去掉 ON UPDATE CURRENT_TIMESTAMP（DM 不支持，由 MyBatis-Plus MetaObjectHandler 自动填充 updated_at）
    //   - UNIQUE KEY → CONSTRAINT ... UNIQUE（DM 不认 UNIQUE KEY 子句，MySQL 也兼容 CONSTRAINT 写法）
    //   - 去掉 ENGINE/CHARSET 子句（DM 忽略，DM 用 CHARSET 参数控制）
    private static final String CREATE_SQ_USER_SQL = """
        CREATE TABLE IF NOT EXISTS sq_user (
            id            BIGINT       NOT NULL AUTO_INCREMENT,
            username      VARCHAR(64)  NOT NULL,
            password_hash VARCHAR(100) NOT NULL,
            display_name  VARCHAR(64),
            email         VARCHAR(128),
            role          VARCHAR(32)  NOT NULL DEFAULT 'user',
            enabled       TINYINT      NOT NULL DEFAULT 1,
            last_login_at DATETIME,
            created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted       TINYINT      NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            CONSTRAINT uk_username UNIQUE (username)
        )
        """;

    private static final String CREATE_SQ_ROLE_SCENARIO_SQL = """
        CREATE TABLE IF NOT EXISTS sq_role_scenario (
            id           BIGINT      NOT NULL AUTO_INCREMENT,
            role         VARCHAR(32) NOT NULL,
            scenario_id  BIGINT      NOT NULL,
            created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted      TINYINT     NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            CONSTRAINT uk_role_scenario UNIQUE (role, scenario_id)
        )
        """;

    /**
     * 给 sq_scenario 加 ui_config 列（若已存在则跳过；MySQL 8.0.29+ 支持 IF NOT EXISTS，
     * DM8 不支持 ADD COLUMN IF NOT EXISTS 但 ORA-compatible 模式可重复执行报错由 catch 兜底）。
     */
    private static final String ALTER_SQ_SCENARIO_ADD_UI_CONFIG_SQL =
        "ALTER TABLE sq_scenario ADD COLUMN ui_config TEXT";

    /**
     * 给 sq_mining_model 加 user_id 列，用于多租户隔离（同 sq_conversation.user_id）。
     * 列已存在或老库不支持时由 catch 吞错。
     */
    private static final String ALTER_SQ_MINING_MODEL_ADD_USER_ID_SQL =
        "ALTER TABLE sq_mining_model ADD COLUMN user_id VARCHAR(50)";

    private final UserMapper userMapper;
    private final ScenarioMapper scenarioMapper;
    private final RoleScenarioMapper roleScenarioMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @Value("${smart-query.auth.admin-username:admin}")
    private String adminUsername;

    @Value("${smart-query.auth.admin-password:admin123}")
    private String adminPassword;

    @Value("${smart-query.auth.admin-display-name:系统管理员}")
    private String adminDisplayName;

    @Override
    public void run(String... args) {
        jdbcTemplate.execute(CREATE_SQ_USER_SQL);
        jdbcTemplate.execute(CREATE_SQ_ROLE_SCENARIO_SQL);

        // 兼容老库：尝试加 ui_config 列；列已存在时报错被吞掉
        try {
            jdbcTemplate.execute(ALTER_SQ_SCENARIO_ADD_UI_CONFIG_SQL);
            log.info("[SEED] sq_scenario 加列 ui_config 成功");
        } catch (Exception e) {
            log.debug("[SEED] sq_scenario.ui_config 已存在或加列失败（可忽略）: {}", e.getMessage());
        }

        // 兼容老库：尝试给 sq_mining_model 加 user_id 列，用于多租户隔离
        try {
            jdbcTemplate.execute(ALTER_SQ_MINING_MODEL_ADD_USER_ID_SQL);
            log.info("[SEED] sq_mining_model 加列 user_id 成功");
        } catch (Exception e) {
            log.debug("[SEED] sq_mining_model.user_id 已存在或加列失败（可忽略）: {}", e.getMessage());
        }

        seedDefaultAdmin();
        seedDefaultRoleScenarios();
    }

    private void seedDefaultAdmin() {
        Long existing = userMapper.selectCount(new LambdaQueryWrapper<User>()
            .eq(User::getUsername, adminUsername));
        if (existing != null && existing > 0) {
            return;
        }
        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setDisplayName(adminDisplayName);
        admin.setRole("admin");
        admin.setEnabled(1);
        userMapper.insert(admin);
        log.info("[SEED] 已初始化默认管理员账号: {} （首次登录后请尽快修改密码）", adminUsername);
    }

    /**
     * 兜底种子：admin 角色授权全部场景、user 角色授权 general 通用查询。
     * 已存在的授权不重复插入；如果 sq_scenario 表为空（未导入种子）则跳过。
     */
    private void seedDefaultRoleScenarios() {
        List<Scenario> allScenarios = scenarioMapper.selectList(null);
        if (allScenarios.isEmpty()) {
            log.warn("[SEED] sq_scenario 表为空，跳过角色-场景授权兜底（请确认已导入 smart_query_seed.sql）");
            return;
        }

        for (Scenario s : allScenarios) {
            ensureGrant("admin", s.getId());
        }

        Scenario general = allScenarios.stream()
            .filter(s -> "general".equals(s.getCode()))
            .findFirst()
            .orElse(null);
        if (general != null) {
            ensureGrant("user", general.getId());
        }

        log.info("[SEED] 角色-场景授权兜底完成：admin 全部 {} 个场景，user 仅 general",
            allScenarios.size());
    }

    private void ensureGrant(String role, Long scenarioId) {
        Long existing = roleScenarioMapper.selectCount(new LambdaQueryWrapper<RoleScenario>()
            .eq(RoleScenario::getRole, role)
            .eq(RoleScenario::getScenarioId, scenarioId));
        if (existing != null && existing > 0) {
            return;
        }
        RoleScenario rs = new RoleScenario();
        rs.setRole(role);
        rs.setScenarioId(scenarioId);
        roleScenarioMapper.insert(rs);
    }
}
