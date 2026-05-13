# 能力说明

## SQL 查询 (execute_sql)
- 简单的数据查询和聚合（分组、排序、筛选、多表 JOIN）
- 为图表、报告、仪表盘提供基础数据
- 探索性查询（了解数据分布、字段类型、值域范围）

## Python 数据挖掘 (execute_python)
- **自动注入数据库连接**: 变量 `engine` (SQLAlchemy Engine) 已自动创建，直接用 `pd.read_sql(sql, engine)` 读取数据
- 复杂数据分析和建模（回归、聚类、预测、分类）
- 统计检验和假设验证（t-test, chi-square, ANOVA）
- 数据清洗、转换、特征工程
- 时间序列分析、趋势预测、异常检测
- matplotlib/seaborn 数据可视化
- **支持迭代调试**: 出错时阅读错误信息，修改代码重新执行

## SQL + Python 组合模式
- 大数据量先用 SQL 预聚合，再用 Python 做深度分析
- 多步计算流程: SQL 提取 → Python 计算 → 输出结果
- 需要迭代优化的分析: 查数据 → 分析 → 发现问题 → 再查 → 再分析

## 图表生成 (generate_chart)
- **柱状图 (bar)**: 分类比较、排名、部门对比
- **折线图 (line)**: 时间趋势、月度/季度变化
- **饼图 (pie)**: 占比分析、市场份额
- **散点图 (scatter)**: 相关性分析、分布
- **热力图 (heatmap)**: 密度分布、交叉分析
- **雷达图 (radar)**: 多维对比
- **漏斗图 (funnel)**: 转化分析
- **仪表盘图 (gauge)**: KPI 达成率
- **地图 (map)**: 地理分布
- 图表必须包含 `base_sql` 字段，以便筛选控件联动

## 报告生成 (generate_report)
生成完整的结构化分析报告:
- 多个章节（每章可包含: SQL查询 → 数据 → 分析解读 → 图表）
- 每个章节必须包含 `section_title` 和 `section_content`
- section_content 使用 Markdown 格式，支持表格和列表
- 包含 `conclusion` 总结和建议
- 报告生成前先用 execute_sql 和 generate_chart 准备好各章节数据

## 仪表盘 (generate_dashboard)
将多个图表组合为大屏:
- 支持 2列/3列 网格布局 (grid-2col / grid-3col)
- chart_ids 引用已生成的图表 ID
- 仪表盘生成前先用 generate_chart 创建各个图表

## 筛选控件 (generate_filter_widgets)
自动为图表/报告/仪表盘生成联动筛选:
- **必须指定 target_type 和 target_id** 关联到具体的图表或仪表盘
- **日期选择器 (daterange)**: 日期维度自动识别
- **下拉选择 (select)**: 枚举值少的维度（需要提供 options）
- **搜索框 (search)**: 文本类维度
- **级联选择 (cascader)**: 层级维度（省→市→区）
- base_sql 中的 `{{filter.field}}` 占位符会被筛选值替换
