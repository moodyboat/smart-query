# chart_generate - 图表生成

## 描述
根据查询数据生成 ECharts 图表配置。输出标准的 ECharts option JSON，前端可直接渲染。

## 使用场景
- 可视化查询结果
- 生成趋势图、对比图、分布图等
- 为报告和仪表盘提供图表组件

## 图表类型选择指南
| 数据特征 | 推荐类型 |
|---------|---------|
| 分类对比 | bar（柱状图） |
| 时间趋势 | line（折线图） |
| 占比分布 | pie（饼图） |
| 多维关系 | scatter（散点图） |
| 地理分布 | map（地图） |
| 矩阵热力 | heatmap（热力图） |
| 漏斗转化 | funnel（漏斗图） |
| 雷达对比 | radar（雷达图） |
| KPI 达成 | gauge（仪表盘图） |
| 股票/K线 | candlestick（K线图） |
| 层级占比 | treemap（矩形树图） |
| 层级展开 | sunburst（旭日图） |
| 关系网络 | graph（关系图） |
| 数据分布 | boxplot（箱线图） |
| 流向转化 | sankey（桑基图） |
| 事件河流 | themeRiver（主题河流图） |

## ECharts Option 格式
输出必须是完整的 ECharts option JSON 对象:

```json
{
  "title": {"text": "图表标题"},
  "tooltip": {"trigger": "axis"},
  "xAxis": {"type": "category", "data": ["类目1", "类目2"]},
  "yAxis": {"type": "value"},
  "series": [{"type": "bar", "data": [100, 200]}]
}
```

## 关键: base_sql 字段
**每个图表必须包含 `base_sql` 字段**，这是图表数据来源的 SQL 查询。筛选控件通过替换 base_sql 中的 `{{filter.field}}` 占位符来动态更新图表数据。

```json
{
  "chart_type": "bar",
  "title": "各区域销售额",
  "echarts_option": { ... },
  "base_sql": "SELECT region, SUM(amount) as total FROM orders WHERE 1=1 {{filter.region}} {{filter.date}} GROUP BY region"
}
```

base_sql 编写规则:
1. 使用 `WHERE 1=1` 作为基础条件，方便追加筛选
2. 每个可筛选维度对应一个 `{{filter.字段名}}` 占位符
3. 日期维度: `{{filter.order_date}}` → 替换为 `AND order_date BETWEEN '2024-01-01' AND '2024-12-31'`
4. 分类维度: `{{filter.region}}` → 替换为 `AND region = '华东'`
5. 即使当前不需要筛选，也要写好占位符，方便后续添加筛选控件

## 设计规范
- 颜色使用专业配色方案（蓝系为主，辅以橙、绿、红）
- 标题清晰表达数据含义
- tooltip 展示完整数据（数值+单位+百分比）
- 图例说明数据系列
- 坐标轴标签避免重叠（可旋转 30°-45°）
- 数据点超过 15 个时，自动添加 dataZoom 滑块
- 添加 toolbox（saveAsImage, dataZoom, restore）方便用户交互
- 数值轴添加 axisLabel.formatter 避免大数字溢出（如 10000 → 1万）

## 增强交互 (ECharts option 模板)

### 折线图/柱状图
```json
{
  "tooltip": {"trigger": "axis", "confine": true},
  "toolbox": {
    "feature": {
      "saveAsImage": {"title": "保存图片"},
      "dataZoom": {"title": {"zoom": "区域缩放", "back": "还原"}},
      "restore": {"title": "还原"}
    }
  },
  "dataZoom": [
    {"type": "slider", "start": 0, "end": 100, "height": 20}
  ]
}
```

### 饼图
```json
{
  "tooltip": {"trigger": "item", "formatter": "{b}: {c} ({d}%)"},
  "legend": {"orient": "vertical", "left": "left", "top": "middle"},
  "series": [{"type": "pie", "radius": ["40%", "70%"], "label": {"formatter": "{b}\n{d}%"}, "emphasis": {"itemStyle": {"shadowBlur": 10}}}]
}
```

### 散点图
```json
{
  "tooltip": {"trigger": "item", "formatter": "{b}: ({c})"},
  "xAxis": {"type": "value", "name": "X轴名称", "splitLine": {"show": true}},
  "yAxis": {"type": "value", "name": "Y轴名称"},
  "series": [{"type": "scatter", "symbolSize": 8, "emphasis": {"itemStyle": {"shadowBlur": 10}}}]
}
```

### 桑基图（流向/转化）
```json
{
  "tooltip": {"trigger": "item"},
  "series": [{"type": "sankey", "layout": "orient-horizontal", "label": {"position": "left"}, "lineStyle": {"color": "gradient", "curveness": 0.5}, "data": [{"name": "阶段A"}], "links": [{"source": "阶段A", "target": "阶段B", "value": 100}]}]
}
```

### 关系图（网络）
```json
{
  "tooltip": {"trigger": "item"},
  "series": [{"type": "graph", "layout": "force", "force": {"repulsion": 200, "edgeLength": [100, 200]}, "roam": true, "label": {"show": true, "position": "right"}, "data": [{"name": "节点A", "symbolSize": 30}], "links": [{"source": "节点A", "target": "节点B"}]}]
}
```

### 箱线图（分布）
```json
{
  "tooltip": {"trigger": "item"},
  "xAxis": {"type": "category", "data": ["类别A", "类别B"]},
  "yAxis": {"type": "value"},
  "series": [{"type": "boxplot", "data": [[10, 20, 30, 40, 50]]}]
}
```

## 输入
```json
{
  "chart_type": "bar",
  "title": "各区域销售额",
  "echarts_option": {
    "title": {"text": "各区域销售额"},
    "tooltip": {"trigger": "axis"},
    "xAxis": {"type": "category", "data": ["华东", "华南", "华北"]},
    "yAxis": {"type": "value"},
    "series": [{"type": "bar", "data": [15000, 12000, 8000]}]
  },
  "base_sql": "SELECT region, SUM(amount) as total FROM orders WHERE 1=1 {{filter.region}} {{filter.order_date}} GROUP BY region"
}
```

## 与其他工具配合
- 生成图表后，可调用 `generate_filter_widgets` 为该图表添加筛选控件
- 多个图表可调用 `generate_dashboard` 组合为仪表盘
- 图表可嵌入 `generate_report` 的章节中
