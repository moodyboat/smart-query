# schema_explore - 数据库表结构探索

## 描述
探索数据库的表结构、字段信息和数据样本。用于在生成 SQL 或数据分析之前了解数据库 schema。

## 使用场景
- 用户提问涉及不熟悉的表或字段
- 需要确认表名、字段名或数据类型
- 查看表的样本数据以了解数据分布

## 操作类型

### list_tables
列出当前数据源的所有表和注释。

输入: `{"action": "list_tables"}`

### describe_table
查看指定表的完整结构（字段名、类型、注释、索引）。

输入: `{"action": "describe_table", "table_name": "表名"}`

### sample_data
查看指定表的样本数据（默认10行）。

输入: `{"action": "sample_data", "table_name": "表名", "limit": 10}`

## 指引
- 先 list_tables 了解全局，再 describe_table 查看感兴趣的表
- 如果表名不确定，用 sample_data 快速了解数据内容
- 注意表注释和字段注释，它们通常包含业务含义
