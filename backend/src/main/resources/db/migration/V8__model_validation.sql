-- V8: Add validation config columns for mining models
ALTER TABLE sq_mining_model
  ADD COLUMN validation_mode VARCHAR(30) DEFAULT 'train_test' COMMENT 'train_test / cv / oos / temporal',
  ADD COLUMN cv_folds INT DEFAULT 5 COMMENT '交叉验证折数',
  ADD COLUMN test_size DOUBLE DEFAULT 0.2 COMMENT '测试集比例',
  ADD COLUMN temporal_column VARCHAR(100) COMMENT '时间列(用于时序验证)',
  ADD COLUMN validation_metrics TEXT COMMENT '验证指标JSON(样本外/时序验证结果)';
