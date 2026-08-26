-- Ownership isolation for pipelines and training execution audit.
-- Existing null-owned rows intentionally remain admin-only.
ALTER TABLE sq_mining_pipeline ADD COLUMN user_id VARCHAR(50);
ALTER TABLE sq_model_execution ADD COLUMN triggered_by_user_id VARCHAR(50);

CREATE INDEX idx_pipeline_user ON sq_mining_pipeline(user_id);
CREATE INDEX idx_execution_trigger_user ON sq_model_execution(triggered_by_user_id);
