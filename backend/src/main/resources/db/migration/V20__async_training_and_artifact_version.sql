-- Real asynchronous training progress and versioned model artifacts.
ALTER TABLE sq_model_execution
    MODIFY COLUMN trigger_type VARCHAR(30) NOT NULL DEFAULT 'manual',
    MODIFY COLUMN status ENUM('pending','queued','running','success','failed','canceled') NOT NULL DEFAULT 'pending',
    ADD COLUMN progress_percent INT NOT NULL DEFAULT 0,
    ADD COLUMN current_stage VARCHAR(50),
    ADD COLUMN progress_message VARCHAR(500),
    ADD COLUMN cancel_requested TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN artifact_path VARCHAR(1000),
    ADD COLUMN artifact_sha256 VARCHAR(64),
    ADD COLUMN artifact_schema_version INT,
    ADD COLUMN started_at DATETIME,
    ADD COLUMN finished_at DATETIME,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

ALTER TABLE sq_mining_model
    ADD COLUMN artifact_sha256 VARCHAR(64),
    ADD COLUMN artifact_schema_version INT;

CREATE INDEX idx_execution_model_status ON sq_model_execution(model_id, status);
