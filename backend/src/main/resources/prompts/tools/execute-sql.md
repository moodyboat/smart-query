# execute_sql - SQL 查询执行

## 描述
执行 SQL 查询并返回结果。支持 SELECT、SHOW、DESCRIBE、EXPLAIN 语句。

## 使用场景
- 根据用户问题生成并执行 SQL 查询
- 数据探索和验证
- 为图表、报告、分析提供数据

## 生成规则
1. 只生成只读 SQL（SELECT/SHOW/DESCRIBE/EXPLAIN）
2. 自动添加 LIMIT 子句（默认 1000 行）
3. 使用参数化思维：先理解问题，再选择表，再构建 SQL
4. 对于复杂查询，先写子查询验证中间结果

## SQL 编写指南
- 表名和字段名使用反引号包裹（防止关键字冲突）
- WHERE 条件优先使用索引字段
- 聚合查询必须 GROUP BY 非聚合字段
- 使用 COALESCE 处理 NULL 值
- 日期查询注意时区和格式（默认 Asia/Shanghai）
- 大表查询优先使用 LIMIT

## 占位符支持
SQL 中可使用 `{{filter.字段名}}` 占位符，用于筛选控件动态替换。
当 SQL 结果将用于生成图表时，建议使用占位符模式:

```sql
-- 标准模式: WHERE 1=1 + 占位符追加
SELECT region, SUM(amount) as total
FROM orders
WHERE 1=1
  {{filter.order_date}}
  {{filter.region}}
GROUP BY region
```

占位符替换规则:
- 用户选择日期范围 → `{{filter.order_date}}` 替换为 `AND order_date BETWEEN '2024-01-01' AND '2024-12-31'`
- 用户选择分类值 → `{{filter.region}}` 替换为 `AND region = '华东'`
- 用户未选择 → 占位符被清除（不影响查询）

## 输入
```json
{
  "sql": "SELECT dept, COUNT(*) as cnt FROM employee GROUP BY dept",
  "data_source_id": 1
}
```

## 输出
返回查询结果的 Markdown 表格，包含列名和数据行。
