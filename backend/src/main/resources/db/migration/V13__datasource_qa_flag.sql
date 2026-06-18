-- V13: Add 'for_question_answering' flag to DataSource
-- This flag controls which data sources are available for Q&A functionality

-- Add the new column to sq_data_source table
ALTER TABLE sq_data_source
ADD COLUMN for_question_answering TINYINT(1) DEFAULT 1 COMMENT '是否可用于问答功能: 1=可用, 0=不可用';

-- Set existing non-system databases as available for Q&A by default
UPDATE sq_data_source
SET for_question_answering = 1
WHERE deleted = 0;
