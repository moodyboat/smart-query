-- Durable async task events and model evaluation/governance configuration.
CREATE TABLE sq_task_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    topic VARCHAR(160) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    event_name VARCHAR(50) NOT NULL,
    payload LONGTEXT NOT NULL,
    terminal TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_event_replay (topic, owner_user_id, id),
    INDEX idx_task_event_created (created_at)
);

ALTER TABLE sq_mining_model
    ADD COLUMN positive_class VARCHAR(255),
    ADD COLUMN group_columns TEXT COMMENT 'JSON array; entity-safe split columns',
    ADD COLUMN oos_table VARCHAR(255) COMMENT 'independent locked OOS table',
    ADD COLUMN oos_filter TEXT,
    ADD COLUMN calibration_method VARCHAR(20) DEFAULT 'none',
    ADD COLUMN threshold_policy TEXT COMMENT 'JSON decision-threshold policy',
    ADD COLUMN governance_policy TEXT COMMENT 'JSON publish gates',
    ADD COLUMN evaluation_status VARCHAR(30) DEFAULT 'pending',
    ADD COLUMN approved_by_user_id VARCHAR(64),
    ADD COLUMN approved_at DATETIME,
    ADD COLUMN monitoring_baseline LONGTEXT,
    ADD COLUMN last_drift_metrics LONGTEXT,
    ADD COLUMN last_drift_at DATETIME;
