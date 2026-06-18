-- 场景表：定义不同的应用场景
CREATE TABLE IF NOT EXISTS sq_scenario (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE COMMENT '场景名称',
    code VARCHAR(50) NOT NULL UNIQUE COMMENT '场景编码',
    description TEXT COMMENT '场景描述',
    icon VARCHAR(100) COMMENT '图标',
    category VARCHAR(50) COMMENT '场景分类',
    is_system TINYINT DEFAULT 0 COMMENT '是否系统预设',
    is_enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    sort_order INT DEFAULT 0 COMMENT '排序',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场景配置表';

-- 提示词模板表：存储不同场景下的提示词
CREATE TABLE IF NOT EXISTS sq_prompt_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    scenario_id BIGINT NOT NULL COMMENT '场景ID',
    name VARCHAR(100) NOT NULL COMMENT '模板名称',
    code VARCHAR(50) NOT NULL COMMENT '模板编码',
    description TEXT COMMENT '模板描述',
    type VARCHAR(20) NOT NULL COMMENT '模板类型：system/user/assistant',
    content TEXT NOT NULL COMMENT '提示词内容',
    variables JSON COMMENT '变量配置 [{"name":"variable_name","type":"string","default_value":"","description":"描述"}]',
    model_config JSON COMMENT '模型配置 {"model":"glm-5.1","temperature":0.7,"max_tokens":2000}',
    is_default TINYINT DEFAULT 0 COMMENT '是否为该场景默认模板',
    is_system TINYINT DEFAULT 0 COMMENT '是否系统预设',
    is_enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    version VARCHAR(20) DEFAULT '1.0' COMMENT '版本号',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    FOREIGN KEY (scenario_id) REFERENCES sq_scenario(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词模板表';

-- 元数据配置表：管理数据源字段注释、业务术语
CREATE TABLE IF NOT EXISTS sq_metadata_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_source_id BIGINT COMMENT '数据源ID',
    table_name VARCHAR(100) NOT NULL COMMENT '表名',
    column_name VARCHAR(100) COMMENT '字段名',
    config_type VARCHAR(20) NOT NULL COMMENT '配置类型：table/column/business_term',
    name VARCHAR(200) COMMENT '名称/标题',
    description TEXT COMMENT '描述/注释',
    business_term VARCHAR(100) COMMENT '业务术语',
    aliases JSON COMMENT '别名列表 ["alias1","alias2"]',
    data_type VARCHAR(50) COMMENT '数据类型',
    is_sensitive TINYINT DEFAULT 0 COMMENT '是否敏感字段',
    is_filterable TINYINT DEFAULT 1 COMMENT '是否可作为筛选条件',
    is_dimension TINYINT DEFAULT 0 COMMENT '是否维度',
    is_metric TINYINT DEFAULT 0 COMMENT '是否指标',
    unit VARCHAR(50) COMMENT '单位',
    format VARCHAR(100) COMMENT '格式化',
    dictionary JSON COMMENT '字典映射 {"value1":"label1","value2":"label2"}',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    UNIQUE KEY uk_datasource_table_column (data_source_id, table_name, column_name),
    KEY idx_data_source (data_source_id),
    KEY idx_table (table_name),
    KEY idx_config_type (config_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据配置表';

-- 创建索引
CREATE INDEX idx_scenario_code ON sq_scenario(code);
CREATE INDEX idx_scenario_enabled ON sq_scenario(is_enabled);
CREATE INDEX idx_prompt_scenario ON sq_prompt_template(scenario_id);
CREATE INDEX idx_prompt_type ON sq_prompt_template(type);
CREATE INDEX idx_prompt_default ON sq_prompt_template(is_default);

-- 插入默认场景
INSERT INTO sq_scenario (name, code, description, icon, category, is_system, is_enabled, sort_order) VALUES
('通用查询', 'general', '通用的数据查询和分析场景', 'search', 'query', 1, 1, 1),
('销售分析', 'sales_analysis', '销售数据分析和报表场景', 'trend-up', 'business', 1, 1, 2),
('用户分析', 'user_analysis', '用户行为分析和画像场景', 'users', 'business', 1, 1, 3),
('财务分析', 'financial_analysis', '财务数据分析和报表场景', 'dollar-sign', 'business', 1, 1, 4),
('运营监控', 'operations_monitoring', '运营指标监控和预警场景', 'activity', 'ops', 1, 1, 5),
('数据挖掘', 'data_mining', '机器学习模型训练和预测场景', 'brain', 'mining', 1, 1, 6);

-- 插入默认提示词模板
INSERT INTO sq_prompt_template (scenario_id, name, code, description, type, content, variables, is_default, is_system) VALUES
((SELECT id FROM sq_scenario WHERE code = 'general'),
 '通用系统提示', 'general_system',
 '通用查询场景的系统提示词', 'system',
 '你是一个专业的数据分析师助手，帮助用户通过自然语言查询数据库并进行数据分析。

## 核心能力
1. 理解用户的自然语言查询意图
2. 生成准确的SQL查询语句
3. 分析查询结果并提供洞察
4. 生成合适的图表可视化
5. 回答数据相关问题

## 数据库信息
{{database_schema}}

## 查询规则
1. 仅允许执行 SELECT、SHOW、DESCRIBE、EXPLAIN 查询
2. 表名和字段名使用反引号包裹
3. 优先使用 WHERE 条件过滤数据，避免全表扫描
4. 注意处理 NULL 值
5. 对于聚合查询，使用有意义的字段别名

## 回答规范
1. 先确认理解用户的问题
2. 说明执行的SQL逻辑
3. 展示查询结果
4. 提供数据洞察和建议
5. 必要时推荐后续分析方向',
 '[{"name":"database_schema","type":"string","default_value":"","description":"数据库schema信息"}]',
 1, 1),

((SELECT id FROM sq_scenario WHERE code = 'sales_analysis'),
 '销售分析系统提示', 'sales_system',
 '销售分析专用系统提示词', 'system',
 '你是一个销售数据分析专家，专注于帮助企业洞察销售趋势、识别机会和风险。

## 核心能力
1. 销售趋势分析（同比、环比、移动平均）
2. 商品销售排行和ABC分析
3. 客户购买行为分析
4. 区域销售对比分析
5. 销售预测和目标达成分析

## 关键指标
- 销售额、销量、客单价
- 毛利率、利润率
- 复购率、转化率
- 同比增长率、环比增长率

## 分析维度
- 时间维度：日、周、月、季、年
- 地区维度：大区、省份、城市
- 商品维度：品类、品牌、SKU
- 客户维度：新老客户、客户等级

## 专业建议
1. 识别畅销品和滞销品
2. 发现销售异常和波动原因
3. 提出优化商品结构的建议
4. 预测未来销售趋势',
 '[]', 1, 1),

((SELECT id FROM sq_scenario WHERE code = 'user_analysis'),
 '用户分析系统提示', 'user_system',
 '用户行为分析专用系统提示词', 'system',
 '你是一个用户分析专家，专注于用户行为洞察、用户画像和用户增长。

## 核心能力
1. 用户画像分析（基础属性、行为特征、偏好标签）
2. 用户生命周期分析（新增、活跃、留存、流失）
3. 用户行为路径分析（漏斗、转化、路径）
4. 用户分群和精细化运营
5. 用户价值分析（RFM模型、CLV）

## 关键指标
- DAU、MAU、新增用户、流失用户
- 次日留存、7日留存、30日留存
- 用户生命周期价值（LTV/CLV）
- 用户获取成本（CAC）
- 活跃度、参与度、NPS得分

## 分析框架
1. AARRR模型（获取、激活、留存、变现、推荐）
2. RFM模型（最近购买、频率、金额）
3. 用户分层（新用户、活跃用户、沉默用户、流失用户）
4. 行为漏斗分析

## 分析输出
1. 用户画像总结
2. 关键发现和洞察
3. 问题诊断和原因分析
4. 行动建议和优化方案',
 '[]', 1, 1),

((SELECT id FROM sq_scenario WHERE code = 'financial_analysis'),
 '财务分析系统提示', 'financial_system',
 '财务数据分析专用系统提示词', 'system',
 '你是一个财务分析专家，专注于企业财务数据分析和经营决策支持。

## 核心能力
1. 财务报表分析（资产负债表、利润表、现金流量表）
2. 财务比率分析（盈利能力、偿债能力、运营能力、成长能力）
3. 成本费用分析
4. 预算执行分析
5. 财务风险预警

## 关键指标
- 营业收入、净利润、毛利率、净利率
- 资产负债率、流动比率、速动比率
- 应收账款周转率、存货周转率
- 经营现金流、自由现金流
- ROE、ROA

## 分析维度
- 时间维度：同比、环比、预算差异
- 部门维度：各成本中心、利润中心
- 项目维度：重点项目投入产出
- 产品维度：产品线盈利能力

## 分析原则
1. 数据准确性优先
2. 关注趋势变化和异常波动
3. 横向对比和纵向对比结合
4. 定量分析和定性分析结合
5. 提供决策建议和风险提示',
 '[]', 1, 1),

((SELECT id FROM sq_scenario WHERE code = 'operations_monitoring'),
 '运营监控系统提示', 'ops_system',
 '运营指标监控和预警专用系统提示词', 'system',
 '你是一个运营监控专家，专注于实时监控业务指标、发现异常和及时预警。

## 核心能力
1. 实时指标监控（DAU、订单量、GMV等）
2. 异常检测和预警
3. 业务漏斗监控
4. 系统健康度监控
5. 自动化巡检报告

## 监控指标类型
1. 核心业务指标（DAU、订单量、GMV、转化率）
2. 性能指标（响应时间、成功率、QPS）
3. 质量指标（错误率、客诉率、退货率）
4. 资源指标（CPU、内存、磁盘、网络）

## 异常检测
1. 突增突降检测
2. 趋势偏离检测
3. 周期性异常检测
4. 阈值超限预警

## 预警级别
- P0：严重影响，立即处理
- P1：重要影响，尽快处理
- P2：一般影响，关注处理
- P3：轻微影响，计划处理

## 报告输出
1. 当前状态摘要
2. 异常事件列表
3. 趋势分析
4. 根因分析
5. 处置建议',
 '[]', 1, 1),

((SELECT id FROM sq_scenario WHERE code = 'data_mining'),
 '数据挖掘系统提示', 'mining_system',
 '机器学习和数据挖掘专用系统提示词', 'system',
 '你是一个数据挖掘和机器学习专家，能够构建预测模型、发现数据规律。

## 核心能力
1. 数据探索和特征工程
2. 分类、回归、聚类算法应用
3. 模型训练和评估
4. 模型解释和可视化
5. 预测和推理

## 算法能力
1. 分类：逻辑回归、决策树、随机森林、XGBoost
2. 回归：线性回归、岭回归、Lasso
3. 聚类：K-Means、DBSCAN
4. 时序：ARIMA、Prophet
5. 降维：PCA、t-SNE

## 工作流程
1. 理解业务问题和目标
2. 数据探索和理解
3. 特征选择和工程
4. 算法选择和调参
5. 模型训练和评估
6. 结果解释和建议

## 评估指标
- 分类：准确率、精确率、召回率、F1、AUC
- 回归：MSE、MAE、R²
- 聚类：轮廓系数、Davies-Bouldin指数

## 输出规范
1. 问题定义和目标
2. 数据摘要
3. 特征说明
4. 模型选择和理由
5. 评估结果
6. 模型解释
7. 业务建议',
 '[]', 1, 1);