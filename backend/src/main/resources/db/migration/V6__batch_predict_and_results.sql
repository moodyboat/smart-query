-- V6: 批量预测 + 结果持久化 + 训练校验

-- 预测结果表：统一存放所有模型预测结果
CREATE TABLE IF NOT EXISTS sq_prediction_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    model_id BIGINT NOT NULL COMMENT '关联模型ID',
    model_name VARCHAR(200) COMMENT '模型名称(冗余)',
    batch_id VARCHAR(50) COMMENT '批量预测批次ID',
    trigger_type VARCHAR(30) NOT NULL DEFAULT 'manual' COMMENT 'manual/schedule',
    model_execution_id BIGINT COMMENT '关联正式模型执行记录',
    input_data JSON COMMENT '输入特征值',
    prediction VARCHAR(500) COMMENT '预测结果',
    probability DOUBLE COMMENT '预测概率/置信度',
    result_table VARCHAR(200) COMMENT '结果写入的业务表名',
    predicted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '预测时间',
    INDEX idx_model (model_id),
    INDEX idx_batch (batch_id),
    INDEX idx_predicted (predicted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 模型表增加批量预测字段
ALTER TABLE sq_mining_model ADD COLUMN predict_input_table VARCHAR(200) COMMENT '批量预测：输入数据表';
ALTER TABLE sq_mining_model ADD COLUMN predict_result_table VARCHAR(200) COMMENT '批量预测：结果保存表';
ALTER TABLE sq_mining_model ADD COLUMN schedule_mode VARCHAR(20) NOT NULL DEFAULT 'train' COMMENT '调度模式: train=定期重训, predict=定期预测';

-- 模型持久化目录（从/tmp迁移到用户目录下的持久位置）
-- 应用层处理，不需要SQL
