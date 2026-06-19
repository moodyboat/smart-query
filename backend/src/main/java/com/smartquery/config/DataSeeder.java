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

    private static final String CREATE_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS sq_user (
            id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
            username      VARCHAR(64)  NOT NULL COMMENT '用户名（登录名）',
            password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
            display_name  VARCHAR(64)  COMMENT '显示名',
            email         VARCHAR(128) COMMENT '邮箱',
            role          VARCHAR(32)  NOT NULL DEFAULT 'user' COMMENT '角色: admin/user',
            enabled       TINYINT      NOT NULL DEFAULT 1      COMMENT '是否启用: 0 禁用 1 启用',
            last_login_at DATETIME                             COMMENT '最后登录时间',
            created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
            deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除: 0 未删 1 已删',
            PRIMARY KEY (id),
            UNIQUE KEY uk_username (username)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表'
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
