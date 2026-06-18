-- 添加图表图片路径字段到sq_chart表
ALTER TABLE sq_chart ADD COLUMN image_path VARCHAR(500) COMMENT '图表图片文件路径' AFTER echarts_option;

-- 添加索引以提高查询性能
CREATE INDEX idx_image_path ON sq_chart(image_path);
