-- =====================================================================
-- 老库迁移脚本：场景能力增强（ui_config 列 + sq_role_scenario 授权表）
-- =====================================================================
-- 适用场景：已部署的 smart-query 库（已有 sq_scenario 表，但无 ui_config 列、无 sq_role_scenario 表）
-- 全新部署：直接导入 smart_query_seed.sql 即可，本脚本可跳过
--
-- 执行方式：mysql -u root -p smart_query < scripts/migrate_add_scenario_ui_config.sql
-- 兼容 DM8：DDL 已按 COMPATIBLE_MODE=4 调整
-- =====================================================================

-- 1. 扩展 sq_scenario 表，新增 ui_config 列
--    MySQL 8.0.29+ 支持 IF NOT EXISTS，DM8 不支持但重复执行报错可忽略
ALTER TABLE sq_scenario ADD COLUMN ui_config TEXT COMMENT '前端UI配置 JSON: {theme, avatar, welcome, capabilities, examples}';

-- 2. 回填 6 个系统场景的 ui_config 数据（与前端 scenarios.js 对齐）
UPDATE sq_scenario SET ui_config = '{"theme":{"primary":"#409EFF","gradient":"linear-gradient(135deg, #667eea 0%, #764ba2 100%)","background":"#f5f7fa","headerBg":"linear-gradient(135deg, #667eea 0%, #764ba2 100%)","cardBg":"rgba(255, 255, 255, 0.9)"},"avatar":{"emoji":"🔍","fallbackColor":"#667eea","size":"large"},"welcome":{"title":"欢迎使用智能问数","subtitle":"不只是查询 — 我可以帮你做完整的数据分析","description":"我是你的智能数据分析助手，可以帮助你查询数据库、生成图表、分析数据。"},"capabilities":[{"icon":"SQL","iconColor":"#E6A23C","title":"智能查询","description":"用自然语言查询数据库，自动生成 SQL"},{"icon":"Py","iconColor":"#409EFF","title":"数据挖掘","description":"Python 分析、建模、预测，支持迭代调试"},{"icon":"📊","iconColor":"#67C23A","title":"可视化","description":"ECharts 图表、仪表盘大屏，自动筛选联动"},{"icon":"📋","iconColor":"#F56C6C","title":"分析报告","description":"多表查询、计算分析、结构化报告生成"}],"examples":["各区域销售额对比，生成柱状图","用Python分析客户流失原因","建一个员工薪资分类预测模型","生成本月销售分析报告","做一个销售仪表盘大屏"]}' WHERE code = 'general';

UPDATE sq_scenario SET ui_config = '{"theme":{"primary":"#67C23A","gradient":"linear-gradient(135deg, #11998e 0%, #38ef7d 100%)","background":"#f0fff4","headerBg":"linear-gradient(135deg, #11998e 0%, #38ef7d 100%)","cardBg":"rgba(255, 255, 255, 0.95)"},"avatar":{"emoji":"📈","fallbackColor":"#11998e","size":"large"},"welcome":{"title":"销售数据分析专家","subtitle":"专注于帮助企业洞察销售趋势、识别机会和风险","description":"我是销售数据分析专家，可以帮助你进行销售趋势分析、商品排行分析、客户行为分析等。"},"capabilities":[{"icon":"📊","iconColor":"#67C23A","title":"销售趋势分析","description":"同比、环比、移动平均趋势分析"},{"icon":"🏆","iconColor":"#E6A23C","title":"商品ABC分析","description":"畅销品识别、滞销品预警、结构优化"},{"icon":"👥","iconColor":"#409EFF","title":"客户行为分析","description":"购买习惯、复购分析、客户分层"},{"icon":"🗺️","iconColor":"#F56C6C","title":"区域销售对比","description":"大区对比、省份排名、城市渗透"}],"examples":["分析最近30天销售趋势，识别TOP3畅销产品","对比各区域销售额，找出增长最快的大区","按商品品类分析销售额和利润率","分析客户复购率和客单价变化趋势","预测下季度销售额并给出目标建议"]}' WHERE code = 'sales_analysis';

UPDATE sq_scenario SET ui_config = '{"theme":{"primary":"#409EFF","gradient":"linear-gradient(135deg, #667eea 0%, #764ba2 100%)","background":"#f0f7ff","headerBg":"linear-gradient(135deg, #667eea 0%, #764ba2 100%)","cardBg":"rgba(255, 255, 255, 0.95)"},"avatar":{"emoji":"👥","fallbackColor":"#667eea","size":"large"},"welcome":{"title":"用户分析专家","subtitle":"专注于用户行为洞察、用户画像和用户增长","description":"我是用户分析专家，可以帮助你进行用户画像分析、生命周期分析、行为路径分析等。"},"capabilities":[{"icon":"👤","iconColor":"#409EFF","title":"用户画像分析","description":"基础属性、行为特征、偏好标签"},{"icon":"🔄","iconColor":"#67C23A","title":"生命周期分析","description":"新增、活跃、留存、流失分析"},{"icon":"🎯","iconColor":"#E6A23C","title":"行为路径分析","description":"漏斗分析、转化分析、路径优化"},{"icon":"💰","iconColor":"#F56C6C","title":"用户价值分析","description":"RFM模型、CLV预测、价值分层"}],"examples":["分析DAU和MAU的变化趋势，找出流失原因","计算次日留存和7日留存，评估用户粘性","用RFM模型对用户进行分群和精细化运营","分析注册到购买的转化漏斗，找出流失点","预测用户生命周期价值(LTV)和获客成本"]}' WHERE code = 'user_analysis';

UPDATE sq_scenario SET ui_config = '{"theme":{"primary":"#E6A23C","gradient":"linear-gradient(135deg, #f093fb 0%, #f5576c 100%)","background":"#fffaf0","headerBg":"linear-gradient(135deg, #f093fb 0%, #f5576c 100%)","cardBg":"rgba(255, 255, 255, 0.95)"},"avatar":{"emoji":"💰","fallbackColor":"#f5576c","size":"large"},"welcome":{"title":"财务分析专家","subtitle":"专注于企业财务数据分析和经营决策支持","description":"我是财务分析专家，可以帮助你进行财务报表分析、财务比率分析、成本费用分析等。"},"capabilities":[{"icon":"📊","iconColor":"#E6A23C","title":"财务报表分析","description":"资产负债表、利润表、现金流量表"},{"icon":"📈","iconColor":"#67C23A","title":"财务比率分析","description":"盈利能力、偿债能力、运营能力指标"},{"icon":"💵","iconColor":"#409EFF","title":"成本费用分析","description":"成本结构、费用控制、预算执行"},{"icon":"⚠️","iconColor":"#F56C6C","title":"财务风险预警","description":"流动性风险、偿债风险、经营风险"}],"examples":["分析本季度财务报表，计算关键财务比率","对比今年与去年的利润表，分析收入增长驱动因素","评估公司的偿债能力和财务风险","分析各部门的费用执行情况和预算差异","预测下季度现金流并给出资金管理建议"]}' WHERE code = 'financial_analysis';

UPDATE sq_scenario SET ui_config = '{"theme":{"primary":"#F56C6C","gradient":"linear-gradient(135deg, #ff6b6b 0%, #ee5a24 100%)","background":"#fff5f5","headerBg":"linear-gradient(135deg, #ff6b6b 0%, #ee5a24 100%)","cardBg":"rgba(255, 255, 255, 0.95)"},"avatar":{"emoji":"📡","fallbackColor":"#ee5a24","size":"large"},"welcome":{"title":"运营监控专家","subtitle":"专注于实时监控业务指标、发现异常和及时预警","description":"我是运营监控专家，可以帮助你进行实时指标监控、异常检测、业务漏斗监控等。"},"capabilities":[{"icon":"📊","iconColor":"#F56C6C","title":"实时指标监控","description":"DAU、订单量、GMV、转化率等核心指标"},{"icon":"🔔","iconColor":"#E6A23C","title":"异常检测预警","description":"突增突降、趋势偏离、阈值超限"},{"icon":"🔄","iconColor":"#67C23A","title":"业务漏斗监控","description":"转化漏斗、流失分析、路径优化"},{"icon":"🖥️","iconColor":"#409EFF","title":"系统健康监控","description":"性能指标、质量指标、资源监控"}],"examples":["监控今日DAU和订单量，发现异常波动","分析注册到支付的转化漏斗，找出流失环节","检查系统性能指标，发现性能瓶颈","监控各渠道的用户获取成本和质量","生成今日运营监控日报"]}' WHERE code = 'operations_monitoring';

UPDATE sq_scenario SET ui_config = '{"theme":{"primary":"#909399","gradient":"linear-gradient(135deg, #434343 0%, #000000 100%)","background":"#f5f5f5","headerBg":"linear-gradient(135deg, #434343 0%, #000000 100%)","cardBg":"rgba(255, 255, 255, 0.95)"},"avatar":{"emoji":"🧠","fallbackColor":"#434343","size":"large"},"welcome":{"title":"数据挖掘专家","subtitle":"专注于机器学习模型训练、预测和发现数据规律","description":"我是数据挖掘专家，可以帮助你进行数据探索、特征工程、模型训练、预测分析等。"},"capabilities":[{"icon":"🔍","iconColor":"#909399","title":"数据探索分析","description":"数据分布、相关性分析、特征发现"},{"icon":"⚙️","iconColor":"#409EFF","title":"特征工程","description":"特征选择、特征变换、特征优化"},{"icon":"🤖","iconColor":"#67C23A","title":"模型训练评估","description":"分类、回归、聚类算法应用"},{"icon":"📈","iconColor":"#E6A23C","title":"预测和解释","description":"模型预测、结果解释、可视化展示"}],"examples":["探索销售数据的特征分布和相关性","构建客户流失预测模型，评估准确率","用K-means对用户进行聚类分析","训练一个销量预测模型并优化参数","解释模型特征重要性，找出关键影响因素"]}' WHERE code = 'data_mining';

-- 3. 创建角色-场景授权关联表（DDL 兼容 DM8：去 ENGINE/CHARSET、UNIQUE KEY → CONSTRAINT）
CREATE TABLE sq_role_scenario (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    role         VARCHAR(32) NOT NULL,
    scenario_id  BIGINT      NOT NULL,
    created_at   timestamp DEFAULT CURRENT_TIMESTAMP,
    updated_at   timestamp DEFAULT CURRENT_TIMESTAMP,
    deleted      TINYINT     DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_role_scenario UNIQUE (role, scenario_id)
);

-- 4. 初始化默认授权：admin 全部场景、user 仅 general
--    使用子查询而非硬编码 scenario_id，兼容任何 id 序列
INSERT INTO sq_role_scenario (role, scenario_id)
SELECT 'admin', id FROM sq_scenario
WHERE NOT EXISTS (
    SELECT 1 FROM sq_role_scenario rs
    WHERE rs.role = 'admin' AND rs.scenario_id = sq_scenario.id
);

INSERT INTO sq_role_scenario (role, scenario_id)
SELECT 'user', id FROM sq_scenario
WHERE sq_scenario.code = 'general'
  AND NOT EXISTS (
    SELECT 1 FROM sq_role_scenario rs
    WHERE rs.role = 'user' AND rs.scenario_id = sq_scenario.id
);
