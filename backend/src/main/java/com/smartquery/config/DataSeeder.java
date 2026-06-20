package com.smartquery.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.entity.User;
import com.smartquery.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时注入默认管理员账号（密码 BCrypt 加密）。
 * 项目已移除 Flyway，schema 由 smart_query_seed.sql dump 导入；
 * 此处用 CREATE TABLE IF NOT EXISTS 兜底，保证表一定存在（兼容本地老库与 Docker 全新库）。
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
    private static final String CREATE_TABLE_SQL = """
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

    private final UserMapper userMapper;
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
        jdbcTemplate.execute(CREATE_TABLE_SQL);

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
}
