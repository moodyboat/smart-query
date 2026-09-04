-- 智能问数系统 - 数据挖掘流程编排
-- V4__mining_pipeline.sql

-- 挖掘流水线定义
CREATE TABLE IF NOT EXISTS sq_mining_pipeline (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    data_source_id BIGINT NOT NULL,
    conversation_id BIGINT COMMENT '来源对话ID',
    status ENUM('draft','ready','running','completed','failed') NOT NULL DEFAULT 'draft',
    nodes JSON NOT NULL COMMENT '流程节点定义 [{id,type,config,position}]',
    edges JSON COMMENT '节点连线 [{source,target}]',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    INDEX idx_datasource (data_source_id),
    INDEX idx_conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 挖掘模型
CREATE TABLE IF NOT EXISTS sq_mining_model (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    pipeline_id BIGINT COMMENT '关联流水线',
    name VARCHAR(200) NOT NULL,
    description TEXT,
    data_source_id BIGINT NOT NULL,
    conversation_id BIGINT COMMENT '来源对话ID',
    model_type VARCHAR(50) NOT NULL COMMENT 'classification/regression/clustering/anomaly_detection',
    algorithm VARCHAR(100) NOT NULL COMMENT 'random_forest/xgboost/kmeans/...',
    algorithm_version INT COMMENT '固化的算法版本',
    algorithm_snapshot LONGTEXT COMMENT '带校验值的算法实现快照',

    -- 数据配置
    source_table VARCHAR(200) COMMENT '源数据表',
    feature_columns JSON COMMENT '特征列',
    target_column VARCHAR(200) COMMENT '目标列',
    preprocessing JSON COMMENT '预处理配置',

    -- 超参数
    hyperparameters JSON NOT NULL COMMENT '算法超参数 {n_estimators:100, max_depth:5, ...}',

    -- 训练结果
    metrics JSON COMMENT '评估指标 {accuracy:0.95, f1:0.93, ...}',
    feature_importance JSON COMMENT '特征重要性',
    training_log TEXT COMMENT '训练日志',
    model_path VARCHAR(500) COMMENT '持久化模型路径(.pkl)',

    -- 生命周期
    status ENUM('draft','training','trained','published','offline','failed') NOT NULL DEFAULT 'draft',
    version INT NOT NULL DEFAULT 1,

    -- 调度
    schedule_cron VARCHAR(100) COMMENT '调度cron表达式',
    schedule_enabled TINYINT NOT NULL DEFAULT 0,
    last_run_at DATETIME,
    next_run_at DATETIME,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    INDEX idx_pipeline (pipeline_id),
    INDEX idx_datasource (data_source_id),
    INDEX idx_conversation (conversation_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 模型执行历史
CREATE TABLE IF NOT EXISTS sq_model_execution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    model_id BIGINT NOT NULL,
    trigger_type ENUM('manual','schedule','chat') NOT NULL DEFAULT 'manual',
    execution_kind VARCHAR(20) NOT NULL DEFAULT 'TRAIN',
    status ENUM('pending','running','success','failed') NOT NULL DEFAULT 'pending',
    hyperparameters JSON COMMENT '本次执行使用的超参数快照',
    algorithm_id VARCHAR(100),
    algorithm_version INT,
    algorithm_snapshot LONGTEXT COMMENT '本次执行使用的算法快照',
    metrics JSON COMMENT '本次执行结果指标',
    execution_log TEXT,
    output_summary TEXT,
    execution_time_ms INT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_model (model_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
