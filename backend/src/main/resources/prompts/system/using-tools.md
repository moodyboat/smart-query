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

## 错误自动修复

当工具执行失败时，系统会自动分析错误原因并提供修复提示。你应当:

1. **仔细阅读错误信息** — 包含错误类型、位置、详细描述
2. **关注修复提示** — 系统会提示可能的正确列名、表名、或常见原因
3. **分析根因后修正** — 不要盲目重试，要理解错误原因
4. **最多重试 3 次** — 如果 3 次修正后仍然失败，向用户说明问题

### SQL 常见错误模式
| 错误 | 原因 | 修复方法 |
|------|------|----------|
| Unknown column | 列名拼写错误或不存在 | 使用提示的正确列名，或调用 schema_explore |
| Table doesn't exist | 表名错误 | 检查表名拼写，使用 schema_explore 确认 |
| Syntax error | SQL 语法错误 | 检查逗号、括号、引号匹配 |
| Timeout | 查询太慢 | 添加 LIMIT、缩小 WHERE 范围、使用索引 |
| Duplicate column | JOIN 后列名冲突 | 添加表名前缀 t1.col, t2.col |

### Python 常见错误模式
| 错误 | 原因 | 修复方法 |
|------|------|----------|
| NameError | 变量未定义 | 检查拼写、确认已赋值 |
| KeyError | 列名或键不存在 | 用 df.columns 检查列名 |
| ImportError | 库不可用 | 使用已预装的库 |
| TypeError | 类型不匹配 | 用 print(type(x)) 检查 |
| ValueError | 数据范围问题 | 检查数据范围和空值 |

## 数据库结构
如果 system prompt 中已包含数据库表结构信息，直接基于这些信息生成 SQL，无需再调用 schema_explore。仅在表结构信息不完整或缺失时才使用 schema_explore。
