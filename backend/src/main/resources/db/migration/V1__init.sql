-- 智能问数系统 - 数据库初始化
-- V1__init.sql

-- 会话表
CREATE TABLE IF NOT EXISTS sq_conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL DEFAULT '',
    data_source_id BIGINT,
    user_id VARCHAR(100) DEFAULT 'default',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1-活跃 0-归档',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 聊天消息表
CREATE TABLE IF NOT EXISTS sq_chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    role ENUM('user','assistant','system','tool') NOT NULL,
    content TEXT NOT NULL,
    tool_name VARCHAR(100) COMMENT '工具名称(tool角色时)',
    tool_call_id VARCHAR(100) COMMENT '工具调用ID',
    token_count INT DEFAULT 0,
    model VARCHAR(100) COMMENT '使用的模型',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 数据源配置表
CREATE TABLE IF NOT EXISTS sq_data_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    type ENUM('mysql','postgresql','gbase','oracle','dm') NOT NULL DEFAULT 'mysql',
    host VARCHAR(200) NOT NULL,
    port INT NOT NULL,
    database_name VARCHAR(200) NOT NULL,
    username VARCHAR(200) NOT NULL,
    password VARCHAR(500) NOT NULL,
    extra_config JSON COMMENT '额外JDBC参数',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1-正常 0-禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 数据词典表
CREATE TABLE IF NOT EXISTS sq_data_dict (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_source_id BIGINT NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    table_comment VARCHAR(500) DEFAULT '',
    column_name VARCHAR(200) NOT NULL,
    column_comment VARCHAR(500) DEFAULT '',
    column_type VARCHAR(100) DEFAULT '',
    is_dimension TINYINT DEFAULT 0 COMMENT '1-维度 0-指标',
    sample_values JSON COMMENT '采样值',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_datasource_table (data_source_id, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 查询历史表
CREATE TABLE IF NOT EXISTS sq_query_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT,
    trace_id VARCHAR(100) COMMENT '全链路追踪ID',
    question TEXT COMMENT '原始问题',
    generated_sql TEXT COMMENT '生成的SQL',
    execution_time_ms INT COMMENT '执行耗时',
    row_count INT COMMENT '返回行数',
    total_tokens INT DEFAULT 0,
    model VARCHAR(100) COMMENT '使用的模型',
    cost_usd DECIMAL(10,6) DEFAULT 0 COMMENT '成本(USD)',
    status ENUM('success','error','timeout') DEFAULT 'success',
    error_message TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_trace_id (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- LLM 模型配置表
CREATE TABLE IF NOT EXISTS sq_llm_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    model_code VARCHAR(100) NOT NULL UNIQUE,
    model_name VARCHAR(200) NOT NULL,
    api_url VARCHAR(500) NOT NULL,
    api_key VARCHAR(500) NOT NULL,
    max_tokens INT NOT NULL DEFAULT 4096,
    temperature DECIMAL(3,2) NOT NULL DEFAULT 0.10,
    is_default TINYINT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1-启用 0-禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Python 执行记录表
CREATE TABLE IF NOT EXISTS sq_python_execution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT,
    code TEXT NOT NULL,
    stdout TEXT,
    stderr TEXT,
    exit_code INT,
    execution_time_ms INT,
    status ENUM('pending','running','success','error','timeout') DEFAULT 'pending',
    data_source_id BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 图表定义表
CREATE TABLE IF NOT EXISTS sq_chart (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT,
    title VARCHAR(200),
    chart_type VARCHAR(50) COMMENT 'bar/line/pie/scatter/heatmap/map/etc',
    echarts_option JSON NOT NULL,
    data_source_id BIGINT,
    base_sql TEXT COMMENT '关联的SQL(支持筛选控件联动)',
    filter_bindings JSON COMMENT '筛选控件绑定关系',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 报告定义表
CREATE TABLE IF NOT EXISTS sq_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT,
    title VARCHAR(200),
    sections JSON NOT NULL COMMENT '报告章节列表',
    status ENUM('generating','completed','error') DEFAULT 'generating',
    data_source_id BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 仪表盘定义表
CREATE TABLE IF NOT EXISTS sq_dashboard (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT,
    title VARCHAR(200),
    layout JSON NOT NULL COMMENT '仪表盘布局配置',
    chart_ids JSON NOT NULL COMMENT '包含的图表ID列表',
    filter_widgets JSON COMMENT '全局筛选控件',
    data_source_id BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
