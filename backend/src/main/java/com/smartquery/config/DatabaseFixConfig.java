package com.smartquery.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时补丁：确保 sq_data_source.for_question_answering 列存在（旧库升级路径）。
 * 跨库兼容（MySQL / 达梦 DM8 / GBase）：try ALTER + 容错"列已存在"错误，
 * 不依赖 INFORMATION_SCHEMA（DM 不支持）或 DatabaseMetaData（DM 兼容模式下行为不稳定）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseFixConfig implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            jdbcTemplate.execute(
                "ALTER TABLE sq_data_source ADD COLUMN for_question_answering TINYINT DEFAULT 1"
            );
            jdbcTemplate.execute(
                "UPDATE sq_data_source SET for_question_answering = 1 WHERE deleted = 0"
            );
            log.info("[DB-FIX] Column 'for_question_answering' added successfully");
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase();
            if (msg.contains("already exists") || msg.contains("duplicate column") || msg.contains("已存在")) {
                log.info("[DB-FIX] Column 'for_question_answering' already exists (no-op)");
            } else {
                log.error("[DB-FIX] Failed to add column: {}", e.getMessage());
            }
        }
    }
}
