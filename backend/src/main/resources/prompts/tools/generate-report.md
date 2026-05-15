# report_generate - 分析报告生成

## 描述
生成结构化的数据分析报告，包含多个章节，每章含查询、表格、图表和分析解读。

## 使用场景
- 定期业务报告（周报/月报/季报）
- 专题分析报告
- 数据洞察总结
- 决策支持报告

## 重要: 报告生成工作流
报告不是单步生成的，需要先用其他工具准备数据:

```
Turn 1: execute_sql × N (并行查询各维度数据)
Turn 2: generate_chart × N (为关键章节生成图表)
Turn 3: generate_report (汇总为结构化报告，引用图表和分析)
Turn 4: generate_filter_widgets (为报告添加筛选控件)
```

## 报告结构
```json
{
  "title": "报告标题",
  "sections": [
    {
      "section_title": "总体概况",
      "section_content": "## 核心指标\n\n| 指标 | 本期 | 上期 | 变化 |\n|------|------|------|------|\n| 营收 | 1000万 | 900万 | +11.1% |\n\n分析解读文本...",
      "sql_used": "SELECT ...",
      "chart_type": "bar",
      "chart_id": 15
    },
    {
      "section_title": "区域分析",
      "section_content": "各区域销售表现分析...",
      "sql_used": "SELECT region, SUM(amount) ... GROUP BY region",
      "chart_type": "pie",
      "chart_id": 16
    }
  ],
  "conclusion": "总结与建议\n\n1. 建议1\n2. 建议2"
}
```

## 章节内容格式
每个章节的 `section_content` 使用 Markdown 格式:
- 用 `##` 子标题组织内容
- 用 Markdown 表格展示数据
- 用 `**粗体**` 标注关键数字
- 用列表展示要点
- 包含同比/环比/增长率等计算
- 每章聚焦一个分析维度

## 图表引用 (重要)
如果此前已调用 `generate_chart` 生成了图表，在对应章节中填写 `chart_id` 字段:
- `chart_id`: 之前 `generate_chart` 返回的图表 ID
- 这会在报告章节中嵌入该图表的交互式可视化
- 不需要每个章节都有图表，只为关键数据章节添加
- 如果没有生成过图表，`chart_id` 可以省略

## 编写指南
1. 先通过 SQL 探索数据，确认关键指标和趋势
2. 每个章节聚焦一个分析维度（时间趋势、分类对比、占比分析、关联分析、异常检测）
3. 数据表格 + 文字解读结合
4. 结论基于数据，建议具体可操作
5. 使用对比（同比/环比）增强分析深度
6. 标注数据来源和时间范围
7. 对于复杂计算，先使用 execute_python 计算再写入报告

## 输入
```json
{
  "title": "2024年Q4销售分析报告",
  "sections": [
    {
      "section_title": "总体概况",
      "section_content": "## 核心指标\n\n本季度总营收 **1000万元**，环比增长 **11.1%**...",
      "sql_used": "SELECT SUM(amount) as total FROM orders WHERE quarter='Q4'",
      "chart_type": "bar",
      "chart_id": 15
    }
  ],
  "conclusion": "本季度销售表现良好，建议加大华东区投入..."
}
```

## 与其他工具配合
- 先用 `execute_sql` 和 `execute_python` 准备数据
- 用 `generate_chart` 为关键章节生成可视化
- 生成报告后可调用 `generate_filter_widgets` 添加日期/区域等筛选
