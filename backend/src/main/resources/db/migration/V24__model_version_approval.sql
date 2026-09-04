CREATE TABLE IF NOT EXISTS sq_model_version_approval (
    id BIGINT NOT NULL AUTO_INCREMENT,
    flow_id BIGINT NOT NULL,
    flow_version_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    request_comment TEXT,
    requested_by_user_id VARCHAR(64) NOT NULL,
    reviewer_user_id VARCHAR(64),
    review_comment TEXT,
    reviewed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_model_approval_version UNIQUE (flow_version_id)
);

CREATE INDEX idx_model_approval_status ON sq_model_version_approval(status, created_at);
