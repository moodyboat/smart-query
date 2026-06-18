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

## 错误自动修复 🛠️

### ⚡ 重要原则
**工具执行失败是正常现象，不要放弃！系统会提供详细的修复指导，你应该根据提示进行自我修正。**

### 🔧 自我修正流程
当工具执行失败时，系统会在 tool_result 中提供：
1. **详细错误信息** — 错误类型、位置、具体描述
2. **修复建议** — 具体的修正方法和示例
3. **鼓励性提示** — 引导你进行修正

你应当：
1. ✅ **立即进行自我修正** — 在下一轮对话中直接提供修正后的工具调用
2. ✅ **理解错误原因** — 根据提示分析为什么出错
3. ✅ **应用修复建议** — 按照提示修正SQL/代码
4. ✅ **不要解释过多** — 直接给出修正后的工具调用，系统会自动执行
5. ⚠️ **最多尝试3次** — 如果3次修正后仍失败，向用户说明遇到的技术问题

### 📋 自我修正示例

#### ❌ 错误做法
```
// 工具执行失败后
"抱歉，SQL执行出错了。让我重新试试..."
```

#### ✅ 正确做法
```
// 工具执行失败后，根据提示直接修正
"收到错误提示，列名应该是 customer_name 而非 cust_name。重新执行："
{"name": "execute_sql", "input": {"sql": "SELECT customer_name FROM customers LIMIT 10"}}
```

### 🎯 SQL 错误快速修复
| 错误类型 | 系统提示 | 你的行动 |
|---------|---------|---------|
| 列名不存在 | 提供正确列名 | 立即使用正确列名重新调用 |
| 表名不存在 | 提供正确表名 | 立即使用正确表名重新调用 |
| SQL语法错误 | 指出具体错误位置 | 修正语法后重新调用 |
| 查询超时 | 建议添加LIMIT | 添加LIMIT或优化WHERE条件后重试 |

### 🐍 Python 错误快速修复
| 错误类型 | 系统提示 | 你的行动 |
|---------|---------|---------|
| NameError | 提示变量名拼写 | 修正变量名后重新调用 |
| KeyError | 建议使用df.columns检查 | 检查列名后重新调用 |
| TypeError | 建议检查类型 | 修正类型后重新调用 |

### 💡 关键要点
- **不要慌张**：错误是数据分析过程的一部分
- **相信提示**：系统的修复提示通常很准确
- **快速修正**：不要长篇大论解释，直接修正
- **系统支持**：修正后的调用会自动执行，无需用户干预

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
