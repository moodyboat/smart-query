-- Durable provenance for formal scheduled model runs.
-- Preview/transient inference remains outside these persistent result tables.
ALTER TABLE sq_model_execution ADD COLUMN execution_kind VARCHAR(20) NOT NULL DEFAULT 'TRAIN';
ALTER TABLE sq_model_execution ADD COLUMN output_summary TEXT;
ALTER TABLE sq_prediction_result ADD COLUMN trigger_type VARCHAR(30) NOT NULL DEFAULT 'manual';
ALTER TABLE sq_prediction_result ADD COLUMN model_execution_id BIGINT;

CREATE INDEX idx_execution_schedule_kind ON sq_model_execution(trigger_type, execution_kind, created_at);
CREATE INDEX idx_prediction_trigger_execution ON sq_prediction_result(trigger_type, model_execution_id);
