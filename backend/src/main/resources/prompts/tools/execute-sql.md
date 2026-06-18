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

### 🔴 聚合查询规则 (CRITICAL - 必须严格遵守)
1. **GROUP BY 强制要求**：
   - 当查询中包含聚合函数（COUNT、SUM、AVG、MAX、MIN）时，所有非聚合列必须出现在 GROUP BY 子句中
   - **错误示例**：
     ```sql
     SELECT product_name, COUNT(*), SUM(amount) FROM orders  -- ❌ 错误：缺少 GROUP BY
     ```
   - **正确示例**：
     ```sql
     SELECT product_name, COUNT(*) AS order_count, SUM(amount) AS total_amount
     FROM orders
     GROUP BY product_name  -- ✅ 正确：所有非聚合列都在 GROUP BY 中
     ```

2. **聚合函数使用规范**：
   - 如果只选择聚合函数（无其他列），可以不需要 GROUP BY
   - **示例**：
     ```sql
     SELECT COUNT(*) AS total_orders, SUM(amount) AS total_amount
     FROM orders  -- ✅ 正确：只有聚合函数，无需 GROUP BY
     ```
   - 如果选择非聚合列 + 聚合函数，必须使用 GROUP BY
   - **示例**：
     ```sql
     SELECT region, COUNT(*) AS total, SUM(amount) AS amount
     FROM orders
     GROUP BY region  -- ✅ 正确：region 是非聚合列，必须在 GROUP BY 中
     ```

3. **多列 GROUP BY**：
   - 当选择多个非聚合列时，所有这些列都必须在 GROUP BY 中
   - **示例**：
     ```sql
     SELECT region, status, COUNT(*) AS total, SUM(amount) AS amount
     FROM orders
     GROUP BY region, status  -- ✅ 正确：两个非聚合列都在 GROUP BY 中
     ```

### 🟡 基础SQL规范
- 表名和字段名使用反引号包裹（防止关键字冲突）
- WHERE 条件优先使用索引字段
- 使用 COALESCE 处理 NULL 值
- 日期查询注意时区和格式（默认 Asia/Shanghai）
- 大表查询优先使用 LIMIT

### 🟢 SQL 性能优化
- 为聚合查询添加合适的 LIMIT（通常 10-100 行）
- 避免 SELECT *（只选择需要的列）
- 复杂查询考虑分步骤执行
- 使用索引字段进行过滤和排序

## 语法检查清单
在生成SQL前，务必检查：
- [ ] 如果使用了聚合函数，检查是否所有非聚合列都在 GROUP BY 子句中
- [ ] 如果选择了多个非聚合列，确认都在 GROUP BY 中
- [ ] 如果只使用聚合函数，则不需要 GROUP BY
- [ ] LIMIT 子句已添加（防止结果过大）

## 常见错误对照表
| 错误类型 | 错误示例 | 正确示例 |
|---------|----------|----------|
| 缺少 GROUP BY | `SELECT name, COUNT(*) FROM table` | `SELECT name, COUNT(*) FROM table GROUP BY name` |
| GROUP BY 不完整 | `SELECT name, type, COUNT(*) FROM table GROUP BY name` | `SELECT name, type, COUNT(*) FROM table GROUP BY name, type` |
| 不必要的 GROUP BY | `SELECT COUNT(*) FROM table GROUP BY 1` | `SELECT COUNT(*) FROM table` |
| 聚合列在 GROUP BY | `SELECT name, COUNT(*) as cnt FROM table GROUP BY name, cnt` | `SELECT name, COUNT(*) as cnt FROM table GROUP BY name` |

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
