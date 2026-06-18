-- Check if column exists
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'smart_query' AND TABLE_NAME = 'sq_data_source' AND COLUMN_NAME = 'for_question_answering');

-- Add column if not exists
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE sq_data_source ADD COLUMN for_question_answering TINYINT(1) DEFAULT 1 COMMENT ''是否可用于问答功能: 1=可用, 0=不可用''',
    'SELECT ''Column already exists'' AS message');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Update existing records
UPDATE sq_data_source SET for_question_answering = 1 WHERE deleted = 0 AND for_question_answering IS NULL;

SELECT 'Fix applied' AS result;
