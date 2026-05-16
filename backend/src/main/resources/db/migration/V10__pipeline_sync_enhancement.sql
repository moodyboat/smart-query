-- Pipeline sync enhancement: source tracking and bidirectional sync timestamps
ALTER TABLE sq_mining_pipeline ADD COLUMN source_type VARCHAR(20) DEFAULT 'manual' COMMENT 'manual/chat/auto';
ALTER TABLE sq_mining_pipeline ADD COLUMN last_synced_at DATETIME COMMENT 'Last model-pipeline sync timestamp';

ALTER TABLE sq_mining_model ADD COLUMN last_synced_at DATETIME COMMENT 'Last pipeline-model sync timestamp';
