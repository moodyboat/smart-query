-- V2: Add metadata column for structured tool blocks
ALTER TABLE sq_chat_message ADD COLUMN metadata JSON DEFAULT NULL COMMENT '结构化工具块(JSON)';
