# dashboard_generate - 仪表盘大屏生成

## 描述
将多个图表组合成仪表盘/大屏布局，支持自适应网格和联动筛选。

## 使用场景
- 管理层看板
- 实时监控大屏
- 多维度数据展示
- 运营数据总览

## 重要: chart_ids 引用机制
`chart_ids` 数组中的每个 ID 必须是之前通过 `generate_chart` 工具成功生成的图表 ID。**生成仪表盘前必须先创建好所有图表**。

工作流:
```
Turn 1: execute_sql × 3 (并行查询各维度数据)
Turn 2: generate_chart × 3 (并行生成图表，每个都带 base_sql)
Turn 3: generate_dashboard (组合为仪表盘，引用图表 ID)
Turn 4: generate_filter_widgets (为仪表盘添加全局筛选)
```

## 布局模式
### 两列网格（默认）
```json
{
  "title": "销售监控仪表盘",
  "layout": "grid-2col",
  "chart_ids": [1, 2, 3, 4]
}
```

### 三列网格
```json
{
  "title": "运营数据总览",
  "layout": "grid-3col",
  "chart_ids": [5, 6, 7, 8, 9]
}
```

## 设计规范
- KPI 指标放在顶部（大字体 + 趋势箭头）
- 图表按重要性从上到下排列
- 同维度图表相邻（方便对比）
- 颜色主题统一
- 大屏模式使用深色背景 + 高对比配色

## 输入
```json
{
  "title": "销售监控大屏",
  "chart_ids": [1, 2, 3],
  "layout": "grid-2col"
}
```

## 与筛选控件配合
仪表盘生成后，可调用 `generate_filter_widgets` 添加全局筛选:
- `target_type`: "dashboard"
- `target_id`: 仪表盘的 ID
- 所有图表共享同一组筛选控件
- 筛选变化时，所有图表联动刷新
