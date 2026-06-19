-- 系统用户表
CREATE TABLE IF NOT EXISTS sq_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username        VARCHAR(64)  NOT NULL COMMENT '用户名（登录名）',
    password_hash   VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    display_name    VARCHAR(64)  COMMENT '显示名',
    email           VARCHAR(128) COMMENT '邮箱',
    role            VARCHAR(32)  NOT NULL DEFAULT 'user'  COMMENT '角色: admin/user',
    enabled         TINYINT      NOT NULL DEFAULT 1       COMMENT '是否启用: 0 禁用 1 启用',
    last_login_at   DATETIME                              COMMENT '最后登录时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0       COMMENT '逻辑删除: 0 未删 1 已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 默认管理员账号由 DataSeeder 在首次启动时按 smart-query.auth.admin-* 配置注入（密码 BCrypt 加密），
-- 避免在迁移文件中硬编码无法验证的哈希。
