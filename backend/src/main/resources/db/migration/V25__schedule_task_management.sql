CREATE TABLE IF NOT EXISTS sq_schedule_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    task_type VARCHAR(30) NOT NULL DEFAULT 'MODEL',
    model_id BIGINT,
    flow_version_id BIGINT,
    schedule_mode VARCHAR(20) NOT NULL,
    cron_expression VARCHAR(100) NOT NULL,
    input_table VARCHAR(255),
    input_filter TEXT,
    output_table VARCHAR(255),
    input_payload TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PAUSED',
    owner_user_id VARCHAR(64) NOT NULL,
    last_run_at DATETIME,
    next_run_at DATETIME,
    last_status VARCHAR(30),
    last_error TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

ALTER TABLE sq_model_execution ADD COLUMN schedule_task_id BIGINT;
ALTER TABLE sq_orchestration_run ADD COLUMN schedule_task_id BIGINT;

CREATE INDEX idx_schedule_task_due ON sq_schedule_task(status, next_run_at, deleted);
CREATE INDEX idx_schedule_task_owner ON sq_schedule_task(owner_user_id, deleted, created_at);
CREATE INDEX idx_schedule_task_model ON sq_schedule_task(model_id, schedule_mode, deleted);
CREATE INDEX idx_schedule_task_flow ON sq_schedule_task(flow_version_id, deleted);
CREATE INDEX idx_execution_schedule_task ON sq_model_execution(schedule_task_id, created_at);
CREATE INDEX idx_run_schedule_task ON sq_orchestration_run(schedule_task_id, created_at);
