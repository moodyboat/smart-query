package com.smartquery.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseFixConfig implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            // Check if column exists
            Integer colCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = 'smart_query' AND TABLE_NAME = 'sq_data_source' " +
                "AND COLUMN_NAME = 'for_question_answering'", Integer.class);

            if (colCount != null && colCount == 0) {
                log.info("[DB-FIX] Adding missing column 'for_question_answering' to sq_data_source");
                jdbcTemplate.execute(
                    "ALTER TABLE sq_data_source " +
                    "ADD COLUMN for_question_answering TINYINT(1) DEFAULT 1 " +
                    "COMMENT '是否可用于问答功能: 1=可用, 0=不可用'"
                );
                jdbcTemplate.execute(
                    "UPDATE sq_data_source SET for_question_answering = 1 WHERE deleted = 0"
                );
                log.info("[DB-FIX] Column 'for_question_answering' added successfully");
            } else {
                log.info("[DB-FIX] Column 'for_question_answering' already exists");
            }
        } catch (Exception e) {
            log.error("[DB-FIX] Failed to add column: {}", e.getMessage());
        }
    }
}
