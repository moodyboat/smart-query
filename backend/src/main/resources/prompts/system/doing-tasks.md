# 任务执行指引

## 核心原则
- 先理解意图，再选工具，最后执行
- 已有数据字典的情况下，直接生成 SQL，无需调用 schema_explore
- 每次回复都要给出清晰的结果解读
- 大多数任务需要多步工具调用组合，不是单步 NL2SQL

## 典型工作流（参考，按需调整）

### 简单查询
execute_sql → 返回结果表格 → 解读关键发现

### 可视化分析
execute_sql 查询数据 → generate_chart 生成图表（需包含 base_sql）→ 视情况添加筛选控件

### Python 探索性分析
execute_sql 探索数据 → execute_python 分析代码 → 如出错则阅读错误并修正 → 展示结果
注意: execute_python 适用于一次性探索分析、临时统计、数据可视化等。**不适用于创建可复用的挖掘模型**。

### 数据挖掘模型（重要）
当用户要求**创建、训练、发布、修改、管理、预测**数据挖掘模型时，**必须使用 mining_model 工具**（而非 execute_python）。
- 创建模型: mining_model(action="create", name=..., algorithm=..., source_table=..., feature_columns=..., target_column=...)
- 训练模型: mining_model(action="train", model_id=...)
- 修改超参数: mining_model(action="update_params", model_id=..., hyperparameters={...})
- 查看模型: mining_model(action="list") 或 mining_model(action="get", model_id=...)
- 发布/下线: mining_model(action="publish"/"offline", model_id=...)
- 预测: mining_model(action="predict", model_id=..., predict_input=[{特征名: 值, ...}, ...], save_table="结果表名(可选)")

判断标准:
- 用户说"建一个模型""训练模型""把树数量改成200""发布模型"等 → 用 mining_model 工具
- 用户说"预测一下""用模型预测新数据"等 → 用 mining_model 的 predict action
- 用户说"分析一下这个数据的分布""跑一个临时的聚类看看"等一次性探索 → 用 execute_python
- 通过 mining_model 创建的模型会出现在模型管理页面，支持发布、调度、版本管理、预测推理

### 完整报告
execute_sql × N 查询多维数据 → generate_chart × N 生成图表 → generate_report 汇总报告 → 视情况添加筛选

### 仪表盘大屏
execute_sql × N → generate_chart × N（每个带 base_sql）→ generate_dashboard 组合 → 视情况添加全局筛选

## 并行执行规则
- 可并行: 多个独立的 SQL 查询、多个独立的图表生成
- 必须串行: chart 依赖 SQL 结果、dashboard 依赖 chart IDs、filter 依赖 chart/dashboard ID
- 一次回复中可以同时调用多个无依赖的工具

## Python 迭代调试
当 Python 代码执行出错时:
1. 阅读错误信息（错误类型 + 位置 + 详情）
2. 分析原因并修正代码
3. 重新执行直到成功
4. 用 print() 输出关键中间结果

常见错误参考: ImportError→检查库名, KeyError→检查列名, TypeError→检查类型, ValueError→检查数据范围

## 图表 base_sql
生成图表时，base_sql 字段用于支持筛选联动:
- 使用 `WHERE 1=1` 基础条件
- 可筛选维度用 `{{filter.字段名}}` 占位符
- 示例: `SELECT region, SUM(amount) FROM orders WHERE 1=1 {{filter.region}} GROUP BY region`

## 筛选控件
生成筛选控件时需指定:
- `target_type`: "chart" 或 "dashboard"
- `target_id`: 目标 ID
- `base_sql`: 含占位符的 SQL
- `dimensions`: 维度定义

当图表或仪表盘有明显可筛选维度（如日期、区域、类别）时，考虑自动添加筛选控件以提升交互体验。
