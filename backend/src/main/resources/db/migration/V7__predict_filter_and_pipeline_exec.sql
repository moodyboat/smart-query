-- V7: 输入表筛选条件 + Pipeline执行支持

-- 模型表增加预测输入筛选条件
ALTER TABLE sq_mining_model ADD COLUMN predict_input_filter VARCHAR(500) COMMENT '批量预测输入筛选条件，支持变量: etl_date/today/yesterday/today-N';

-- Pipeline表增加执行状态字段
ALTER TABLE sq_mining_pipeline ADD COLUMN last_executed_at DATETIME COMMENT '上次执行时间';
ALTER TABLE sq_mining_pipeline ADD COLUMN execution_log TEXT COMMENT '执行日志';
