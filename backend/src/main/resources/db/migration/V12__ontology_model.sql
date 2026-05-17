-- Ontology Model: Business semantic layer for intelligent querying
-- V12: Metrics, Dimensions, Glossary, Indicator Config

-- Business Metrics (指标定义)
CREATE TABLE IF NOT EXISTS sq_ontology_metric (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_source_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL COMMENT '指标名称: 如 "月销售额"',
    business_name VARCHAR(200) NOT NULL COMMENT '业务名称: 如 "月度销售总额"',
    description TEXT COMMENT '业务含义描述',

    -- 指标类型和计算
    metric_type ENUM('basic','derived','composite') NOT NULL DEFAULT 'basic'
      COMMENT 'basic=基础指标(直接取字段), derived=派生指标(计算公式), composite=复合指标(多指标组合)',

    -- 数据来源 (basic metrics)
    source_table VARCHAR(200) COMMENT '来源表',
    source_column VARCHAR(200) COMMENT '来源字段',
    aggregation ENUM('sum','count','avg','max','min','count_distinct','none') COMMENT '聚合方式',

    -- 计算公式 (derived/composite metrics)
    formula TEXT COMMENT '计算公式: 如 "SUM(amount) / COUNT(DISTINCT customer_id)"',
    formula_sql_template TEXT COMMENT 'SQL模板: 含 {metric.xxx} 占位符引用其他指标',

    -- 维度关联
    dimensions JSON COMMENT '适用维度列表 ["region","product_category","time_month"]',
    default_grain VARCHAR(50) COMMENT '默认粒度: day/week/month/quarter/year',
    time_column VARCHAR(200) COMMENT '时间维度字段',

    -- 筛选条件
    filter_condition TEXT COMMENT '默认筛选: 如 "status = ''completed''"',

    -- 元数据
    unit VARCHAR(20) COMMENT '单位: 元/个/人/%',
    format_pattern VARCHAR(50) COMMENT '格式化: "#,##0.00"',
    sort_order INT DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1-启用 0-禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    INDEX idx_datasource (data_source_id),
    INDEX idx_source_table (source_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Dimensional Hierarchy (维度层次)
CREATE TABLE IF NOT EXISTS sq_ontology_dimension (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_source_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL COMMENT '维度名: 如 "地区"',
    business_name VARCHAR(200) NOT NULL COMMENT '业务名: 如 "销售区域"',
    description TEXT,

    -- 数据来源
    source_table VARCHAR(200) COMMENT '来源表',
    source_column VARCHAR(200) COMMENT '来源字段',
    dimension_type ENUM('categorical','temporal','numeric_range','hierarchical') NOT NULL DEFAULT 'categorical',

    -- 层次结构
    parent_dimension_id BIGINT COMMENT '父维度ID (用于层次结构如 省→市→区)',
    hierarchy_level INT DEFAULT 0 COMMENT '层次级别: 0=顶级',
    hierarchy_path VARCHAR(500) COMMENT '层次路径: "地区/华东/上海"',
    rollup_column VARCHAR(200) COMMENT '上卷字段: 如 city -> province 的映射字段',

    -- 时间维度特殊字段
    date_format VARCHAR(50) COMMENT '日期格式: YYYY-MM-DD',
    fiscal_year_start VARCHAR(5) DEFAULT '01-01' COMMENT '财年起始月日',

    sort_order INT DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    INDEX idx_datasource (data_source_id),
    INDEX idx_parent (parent_dimension_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Metric-Dimension Associations (指标-维度关联)
CREATE TABLE IF NOT EXISTS sq_ontology_metric_dimension (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    metric_id BIGINT NOT NULL,
    dimension_id BIGINT NOT NULL,
    relationship_type ENUM('dimension_of','filter_by','drill_down') DEFAULT 'dimension_of',
    join_condition TEXT COMMENT '关联条件: 当指标和维度不在同一表时',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_metric_dim (metric_id, dimension_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Business Glossary (业务术语表)
CREATE TABLE IF NOT EXISTS sq_ontology_glossary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_source_id BIGINT NOT NULL,
    term VARCHAR(200) NOT NULL COMMENT '术语: 如 "毛利率"',
    synonyms JSON COMMENT '同义词列表: ["毛利占比","gross_margin"]',
    definition TEXT NOT NULL COMMENT '业务定义',

    -- 映射
    mapped_metric_id BIGINT COMMENT '关联的指标ID',
    mapped_table VARCHAR(200) COMMENT '关联表',
    mapped_column VARCHAR(200) COMMENT '关联字段',
    mapping_rule TEXT COMMENT '映射规则(自然语言描述)',

    usage_examples JSON COMMENT '使用示例: ["上个月毛利率是多少?"]',
    category VARCHAR(100) COMMENT '分类: 财务/销售/库存/人力',
    sort_order INT DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    INDEX idx_datasource (data_source_id),
    FULLTEXT INDEX idx_term (term)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Indicator Definition Table Reference (指标定义表配置)
-- 指向用户的一张表作为"指标定义表"
CREATE TABLE IF NOT EXISTS sq_ontology_indicator_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_source_id BIGINT NOT NULL,
    config_name VARCHAR(200) NOT NULL COMMENT '配置名',

    -- 指标定义表映射
    indicator_table VARCHAR(200) NOT NULL COMMENT '指标定义表名',
    name_column VARCHAR(200) NOT NULL COMMENT '指标名列',
    formula_column VARCHAR(200) COMMENT '公式列',
    category_column VARCHAR(200) COMMENT '分类列',
    unit_column VARCHAR(200) COMMENT '单位列',
    description_column VARCHAR(200) COMMENT '描述列',

    -- 数据来源映射 (如何从指标定义表找到明细数据)
    detail_table_column VARCHAR(200) COMMENT '明细表名列 (指标定义表中的字段,指向明细数据表)',
    detail_filter_column VARCHAR(200) COMMENT '明细表筛选条件列',

    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    INDEX idx_datasource (data_source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
