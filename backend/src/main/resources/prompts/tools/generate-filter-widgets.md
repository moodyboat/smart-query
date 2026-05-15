# filter_widget - 筛选控件生成

## 描述
自动识别数据维度并生成前端筛选控件，支持控件联动查询。**必须指定 target_type 和 target_id 关联到具体的图表或仪表盘。**

## 使用场景
- 为图表添加交互筛选（日期范围、区域选择等）
- 为仪表盘添加全局筛选（一次选择，所有图表联动）
- 为报告添加参数化筛选

## 重要: 目标绑定
筛选控件必须关联到具体的图表或仪表盘，否则前端无法联动:

```json
{
  "base_sql": "SELECT region, SUM(amount) FROM orders WHERE 1=1 {{filter.region}} {{filter.date}} GROUP BY region",
  "target_type": "chart",
  "target_id": 123,
  "dimensions": [
    {"field": "region", "type": "select", "label": "区域", "options": ["华东", "华南", "华北"]},
    {"field": "date", "type": "daterange", "label": "订单日期"}
  ]
}
```

target_type 取值:
- `"chart"` - 绑定到单个图表
- `"dashboard"` - 绑定到仪表盘（所有图表联动）

## 控件类型
| 类型 | 适用场景 | 示例 |
|------|---------|------|
| daterange | 日期范围 | 订单日期、注册时间 |
| select | 枚举选择 | 区域、状态、类型（需提供 options） |
| search | 模糊搜索 | 客户名、产品名 |
| cascader | 级联选择 | 省>市>区、分类>子类 |

## 维度自动识别规则
1. 字段名包含 date/time → daterange
2. 字段类型为 enum/set → select（需查询可选值列表）
3. 字段名包含 name/title → search
4. 字段名包含 area/region/province → cascader

## 占位符联动机制
筛选控件与 base_sql 通过 `{{filter.字段名}}` 占位符绑定:

```sql
-- base_sql 中的占位符
SELECT region, product, SUM(amount)
FROM sales
WHERE 1=1
  {{filter.region}}
  {{filter.order_date}}
GROUP BY region, product
```

当用户选择筛选值时:
- `{{filter.region}}` → 替换为 `AND region = '华东'`
- `{{filter.order_date}}` → 替换为 `AND order_date BETWEEN '2024-01-01' AND '2024-12-31'`
- 未选择时占位符被清除

## select 类型需要提供 options
select 类型必须提供 options 列表。可以通过以下方式获取:
1. 直接从数据字典中获取枚举值
2. 调用 execute_sql 查询: `SELECT DISTINCT region FROM orders`
3. 如果值太多（>20个），考虑改用 search 类型

## 工作流
```
Turn 1: generate_chart (生成图表，带 base_sql)
Turn 2: generate_filter_widgets (为图表添加筛选)
         → target_type: "chart", target_id: 图表ID
```

或:
```
Turn 1: generate_chart × N (生成多个图表)
Turn 2: generate_dashboard (组合为仪表盘)
Turn 3: generate_filter_widgets (为仪表盘添加全局筛选)
         → target_type: "dashboard", target_id: 仪表盘ID
```

## 输入
```json
{
  "base_sql": "SELECT region, product, SUM(amount) FROM sales WHERE 1=1 {{filter.region}} GROUP BY region, product",
  "target_type": "chart",
  "target_id": 1,
  "dimensions": [
    {"field": "region", "type": "select", "label": "区域", "options": ["华东", "华南", "华北", "西部"]},
    {"field": "order_date", "type": "daterange", "label": "订单日期"}
  ]
}
```
