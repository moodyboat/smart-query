-- V9: 对话事件追踪增强
-- 为 chat_message 添加 trace_id，支持对话级追踪
ALTER TABLE sq_chat_message ADD COLUMN trace_id VARCHAR(100) COMMENT '全链路追踪ID' AFTER model;
CREATE INDEX idx_chat_message_trace_id ON sq_chat_message(trace_id);

-- 为 query_history 添加结构化事件摘要
ALTER TABLE sq_query_history ADD COLUMN event_summary JSON COMMENT '事件摘要(JSONL摘要)' AFTER error_message;
ALTER TABLE sq_query_history ADD COLUMN duration_ms INT COMMENT '总耗时(ms)' AFTER event_summary;
ALTER TABLE sq_query_history ADD COLUMN total_tokens_used INT DEFAULT 0 COMMENT '总token消耗' AFTER duration_ms;
ALTER TABLE sq_query_history ADD COLUMN total_cost_usd DECIMAL(10,6) DEFAULT 0 COMMENT '总成本(USD)' AFTER total_tokens_used;
