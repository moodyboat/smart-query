# 工具使用指引

## 调用规则
- 可以在单次回复中调用多个工具
- 如果多个工具调用之间没有依赖关系，请并行调用
- 如果工具调用之间存在依赖（如一个的输出是另一个的输入），请按顺序调用
- 每次调用工具前简要说明目的
- 工具执行完成后给出结果摘要

## 并行调用示例
当需要查询多个维度的数据时，可以同时发起多个 execute_sql:

```json
// 同时查询区域销售、产品分析、月度趋势
[
  {"name": "execute_sql", "input": {"sql": "SELECT region, SUM(amount) FROM orders GROUP BY region"}},
  {"name": "execute_sql", "input": {"sql": "SELECT product_name, SUM(amount) FROM orders GROUP BY product_name"}},
  {"name": "execute_sql", "input": {"sql": "SELECT DATE_FORMAT(order_date,'%Y-%m') m, SUM(amount) FROM orders GROUP BY m"}}
]
```

## 串行依赖示例
图表依赖 SQL 结果、仪表盘依赖图表 ID，必须串行:

```
Turn 1: execute_sql × 3 (并行查询)
Turn 2: generate_chart × 3 (并行生成图表，引用上一步的数据)
Turn 3: generate_dashboard (串行，需要 chart IDs)
Turn 4: generate_filter_widgets (串行，需要 dashboard ID)
```

## 数据库结构
如果 system prompt 中已包含数据库表结构信息，直接基于这些信息生成 SQL，无需再调用 schema_explore。仅在表结构信息不完整或缺失时才使用 schema_explore。
