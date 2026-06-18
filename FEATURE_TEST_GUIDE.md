# 提示词管理系统功能检验指南

## 📋 检验清单

### 一、数据库验证 ✅

**检验目的**：确认数据库表和预设数据是否正确创建

**检验步骤**：

1. **检查数据库表是否存在**
```bash
# 连接到MySQL
mysql -u root -p900110 smart_query

# 查看新表
SHOW TABLES LIKE 'sq_%';
```

**预期结果**：应该看到以下表：
- `sq_scenario` (场景表)
- `sq_prompt_template` (提示词模板表)  
- `sq_metadata_config` (元数据配置表)

2. **验证预设场景数据**
```sql
-- 查看场景数据
SELECT id, name, code, category, is_system, is_enabled 
FROM sq_scenario 
ORDER BY sort_order;
```

**预期结果**：6条预设场景记录
```
+----+-----------------------+-----------------------+----------+-----------+------------+
| id | name                  | code                  | category | is_system | is_enabled |
+----+-----------------------+-----------------------+----------+-----------+------------+
|  1 | 通用查询              | general               | query    |         1 |          1 |
|  2 | 销售分析              | sales_analysis        | business |         1 |          1 |
|  3 | 用户分析              | user_analysis         | business |         1 |          1 |
|  4 | 财务分析              | financial_analysis    | business |         1 |          1 |
|  5 | 运营监控              | operations_monitoring | ops      |         1 |          1 |
|  6 | 数据挖掘              | data_mining           | mining   |         1 |          1 |
+----+-----------------------+-----------------------+----------+-----------+------------+
```

3. **验证预设提示词数据**
```sql
-- 查看提示词模板
SELECT id, scenario_id, name, type, is_default, is_system 
FROM sq_prompt_template 
ORDER BY scenario_id, is_default DESC;
```

**预期结果**：每个场景至少有1个默认的system类型提示词

---

### 二、后端API测试 ✅

**检验目的**：验证所有API端点是否正常工作

#### 2.1 场景管理API

**测试1：获取所有场景**
```bash
curl -s http://localhost:9000/api/v1/scenarios | jq '.'
```

**预期结果**：
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "name": "通用查询",
      "code": "general",
      "description": "通用的数据查询和分析场景",
      "icon": "search",
      "category": "query",
      "isSystem": true,
      "isEnabled": true,
      "sortOrder": 1
    }
    // ... 其他场景
  ]
}
```

**测试2：获取系统预设场景**
```bash
curl -s http://localhost:9000/api/v1/scenarios/system | jq '.'
```

**测试3：根据ID获取场景**
```bash
curl -s http://localhost:9000/api/v1/scenarios/1 | jq '.'
```

**测试4：根据编码获取场景**
```bash
curl -s http://localhost:9000/api/v1/scenarios/code/general | jq '.'
```

#### 2.2 提示词模板API

**测试1：获取所有提示词模板**
```bash
curl -s http://localhost:9000/api/v1/prompt-templates | jq '.'
```

**测试2：获取指定场景的提示词**
```bash
curl -s http://localhost:9000/api/v1/prompt-templates/scenario/1 | jq '.'
```

**预期结果**：应该返回通用查询场景的提示词，包含完整的variables配置

**测试3：获取场景的默认提示词**
```bash
curl -s http://localhost:9000/api/v1/prompt-templates/default/scenario/sales_analysis | jq '.data.name'
```

**预期结果**：应该返回"销售分析系统提示"

**测试4：创建自定义提示词**
```bash
curl -X POST http://localhost:9000/api/v1/prompt-templates \
  -H "Content-Type: application/json" \
  -d '{
    "scenarioId": 1,
    "name": "自定义通用提示",
    "code": "custom_general",
    "description": "我的自定义提示词",
    "type": "system",
    "content": "你是一个AI助手，专注于数据分析。\n\n数据库信息：\n{{database_schema}}",
    "variables": [{"name": "database_schema", "type": "string", "default_value": "", "description": "数据库schema"}],
    "isEnabled": true,
    "version": "1.0"
  }' | jq '.'
```

**测试5：设置默认提示词**
```bash
# 假设刚创建的提示词ID是7
curl -X PUT http://localhost:9000/api/v1/prompt-templates/7/set-default | jq '.'
```

#### 2.3 元数据配置API

**测试1：创建业务术语**
```bash
curl -X POST http://localhost:9000/api/v1/metadata \
  -H "Content-Type: application/json" \
  -d '{
    "dataSourceId": 1,
    "tableName": "",
    "columnName": "",
    "configType": "business_term",
    "name": "销售额",
    "description": "企业销售商品或服务获得的总收入",
    "businessTerm": "销售额",
    "aliases": ["营业额", "营收", "GMV"],
    "dataType": "decimal"
  }' | jq '.'
```

**测试2：获取业务术语列表**
```bash
curl -s http://localhost:9000/api/v1/metadata/type/business_term | jq '.'
```

**测试3：创建表配置**
```bash
curl -X POST http://localhost:9000/api/v1/metadata \
  -H "Content-Type: application/json" \
  -d '{
    "dataSourceId": 1,
    "tableName": "orders",
    "columnName": "",
    "configType": "table",
    "name": "订单表",
    "description": "存储客户订单信息的表"
  }' | jq '.'
```

**测试4：创建字段配置**
```bash
curl -X POST http://localhost:9000/api/v1/metadata \
  -H "Content-Type: application/json" \
  -d '{
    "dataSourceId": 1,
    "tableName": "orders",
    "columnName": "order_amount",
    "configType": "column",
    "name": "订单金额",
    "description": "订单的总金额",
    "businessTerm": "销售额",
    "dataType": "decimal",
    "unit": "元",
    "format": "0.00",
    "isMetric": true,
    "isDimension": false,
    "isFilterable": true
  }' | jq '.'
```

---

### 三、前端界面测试 ✅

#### 3.1 访问前端界面

1. **打开浏览器访问**：
   ```
   http://localhost:5174
   ```

2. **检查导航菜单**：
   - 应该能看到"元数据配置"菜单项
   - 应该能看到"提示词管理"菜单项

#### 3.2 提示词管理界面测试

**测试步骤**：

1. **进入提示词管理界面**
   - 点击侧边栏的"提示词管理"

2. **验证场景展示**
   - 应该看到6个预设场景卡片
   - 每个场景显示：图标、名称、描述、分类标签

3. **选择场景查看提示词**
   - 点击"销售分析"场景
   - 应该看到该场景的提示词列表
   - 默认提示词应该有"默认"标签

4. **查看提示词详情**
   - 点击某个提示词的"查看"按钮
   - 应该弹出详情对话框，显示：
     - 提示词名称、类型、描述
     - 完整的提示词内容
     - 变量配置表格
     - 模型配置信息

5. **创建新提示词**
   - 点击"添加提示词"按钮
   - 填写表单：
     - 名称：`测试提示词`
     - 编码：`test_prompt`
     - 类型：`system`
     - 内容：输入测试内容
   - 点击"保存"
   - 应该在列表中看到新创建的提示词

6. **编辑提示词**
   - 点击非系统预设提示词的"编辑"按钮
   - 修改内容
   - 保存
   - 验证修改生效

7. **设置默认提示词**
   - 点击某个提示词的"设为默认"按钮
   - 该提示词应该获得"默认"标签
   - 其他提示词的"默认"标签应该消失

#### 3.3 元数据管理界面测试

**测试步骤**：

1. **进入元数据管理界面**
   - 点击侧边栏的"元数据配置"

2. **选择数据源**
   - 在数据源选择器中选择一个数据源
   - 应该加载该数据源的元数据

3. **查看表和字段配置**
   - 切换到"表和字段配置"选项卡
   - 应该看到树形结构展示表和字段

4. **查看业务术语**
   - 切换到"业务术语"选项卡
   - 应该看到之前API测试创建的业务术语
   - 可以使用搜索框筛选术语

5. **查看维度指标**
   - 切换到"维度指标"选项卡
   - 应该看到维度列表和指标列表

6. **创建元数据配置**
   - 点击"添加表配置"
   - 填写表单并保存
   - 验证新配置出现在列表中

---

### 四、集成功能测试 ✅

#### 4.1 场景化问数测试

**测试目的**：验证不同场景是否能正确应用对应的提示词

**测试步骤**：

**测试1：通用查询场景**
```bash
# 使用通用查询场景
curl -N http://localhost:9000/api/v1/chat \
  -H "Accept: text/event-stream" \
  -G \
  --data-urlencode "conversationId=1" \
  --data-urlencode "message=查询最近10天的订单数量" \
  --data-urlencode "dataSourceId=1" \
  --data-urlencode "scenario=general"
```

**测试2：销售分析场景**
```bash
# 使用销售分析场景
curl -N http://localhost:9000/api/v1/chat \
  -H "Accept: text/event-stream" \
  -G \
  --data-urlencode "conversationId=2" \
  --data-urlencode "message=分析本月销售趋势，找出畅销产品" \
  --data-urlencode "dataSourceId=1" \
  --data-urlencode "scenario=sales_analysis"
```

**预期行为**：
- 系统应该使用销售分析的专业提示词
- 回复应该包含销售分析专业术语和方法
- 可能提到：同比、环比、ABC分析等概念

**测试3：用户分析场景**
```bash
# 使用用户分析场景  
curl -N http://localhost:9000/api/v1/chat \
  -H "Accept: text/event-stream" \
  -G \
  --data-urlencode "conversationId=3" \
  --data-urlencode "message=分析用户留存率和生命周期价值" \
  --data-urlencode "dataSourceId=1" \
  --data-urlencode "scenario=user_analysis"
```

**预期行为**：
- 回复应该包含用户分析专业术语
- 可能提到：DAU、MAU、留存率、LTV、RFM模型等

#### 4.2 前端场景选择测试

**测试步骤**：

1. **打开主界面**
   ```
   http://localhost:5174
   ```

2. **创建新对话时选择场景**
   - 点击"新建对话"
   - 应该能看到场景选择器
   - 选择"销售分析"场景
   - 发送问题："分析销售趋势"

3. **验证回复特点**
   - 回复应该使用销售分析专业方法
   - 可能包含专业的分析框架和指标

---

### 五、日志和监控 ✅

#### 5.1 查看后端日志

```bash
# 实时查看后端日志
tail -f logs/backend.log

# 查找场景相关日志
grep "SCENARIO" logs/backend.log

# 查找提示词相关日志
grep "PROMPT" logs/backend.log
```

**预期日志内容**：
```
[CTX-ASM] using scenario prompt: sales_analysis
[SCENARIO-PROMPT] 构建场景提示词: sales_analysis, 场景: 销售分析, 长度: 1234
```

#### 5.2 检查数据库查询

```bash
# 查看提示词使用情况
mysql -u root -p900110 smart_query -e "
SELECT 
  s.name as scenario_name,
  COUNT(pt.id) as prompt_count
FROM sq_scenario s
LEFT JOIN sq_prompt_template pt ON s.id = pt.scenario_id
WHERE s.is_enabled = 1
GROUP BY s.id, s.name;
"
```

---

### 六、异常情况测试 ✅

#### 6.1 无效场景测试

```bash
# 使用不存在的场景编码
curl -N http://localhost:9000/api/v1/chat \
  -H "Accept: text/event-stream" \
  -G \
  --data-urlencode "conversationId=1" \
  --data-urlencode "message=测试查询" \
  --data-urlencode "scenario=non_existing_scenario"
```

**预期行为**：系统应该回退到默认场景

#### 6.2 禁用场景测试

```sql
-- 在数据库中禁用一个场景
UPDATE sq_scenario SET is_enabled = 0 WHERE code = 'sales_analysis';
```

然后测试该场景，应该回退到默认场景

#### 6.3 无默认提示词场景测试

```sql
-- 取消场景的默认提示词
UPDATE sq_prompt_template SET is_default = 0 WHERE scenario_id = 2;
```

然后测试该场景，应该有警告日志

---

### 七、性能和稳定性 ✅

#### 7.1 并发测试

```bash
# 使用ab工具进行并发测试
ab -n 100 -c 10 http://localhost:9000/api/v1/scenarios
```

#### 7.2 响应时间测试

```bash
# 测试API响应时间
time curl -s http://localhost:9000/api/v1/scenarios/system > /dev/null
```

---

## 📊 测试结果记录表

| 测试项 | 测试结果 | 备注 |
|--------|----------|------|
| 数据库表创建 | ⬜ 通过/⬜ 失败 |  |
| 预设场景数据 | ⬜ 通过/⬜ 失败 |  |
| 预设提示词数据 | ⬜ 通过/⬜ 失败 |  |
| 场景API获取 | ⬜ 通过/⬜ 失败 |  |
| 提示词API获取 | ⬜ 通过/⬜ 失败 |  |
| 元数据API创建 | ⬜ 通过/⬜ 失败 |  |
| 前端场景展示 | ⬜ 通过/⬜ 失败 |  |
| 前端提示词管理 | ⬜ 通过/⬜ 失败 |  |
| 前端元数据管理 | ⬜ 通过/⬜ 失败 |  |
| 场景化问数-通用 | ⬜ 通过/⬜ 失败 |  |
| 场景化问数-销售分析 | ⬜ 通过/⬜ 失败 |  |
| 场景化问数-用户分析 | ⬜ 通过/⬜ 失败 |  |
| 异常场景回退 | ⬜ 通过/⬜ 失败 |  |

---

## 🔧 快速验证脚本

创建一个快速验证脚本 `quick_test.sh`：

```bash
#!/bin/bash

echo "=== 提示词管理系统快速验证 ==="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 测试函数
test_api() {
    local name=$1
    local url=$2
    local expected=$3
    
    echo -n "Testing $name... "
    result=$(curl -s "$url" | jq -r "$expected" 2>/dev/null)
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ PASSED${NC}"
        return 0
    else
        echo -e "${RED}✗ FAILED${NC}"
        return 1
    fi
}

# API测试
test_api "场景API" "http://localhost:9000/api/v1/scenarios" ".code == 200"
test_api "提示词API" "http://localhost:9000/api/v1/prompt-templates/scenario/1" ".code == 200"
test_api "元数据API" "http://localhost:9000/api/v1/metadata/type/business_term" ".code == 200"

echo ""
echo "=== 快速验证完成 ==="
echo "详细测试请参考完整测试指南"
```

使用方法：
```bash
chmod +x quick_test.sh
./quick_test.sh
```

---

## 💡 提示

1. **按顺序测试**：建议按照上述顺序进行测试，从基础到集成
2. **查看日志**：遇到问题时优先查看后端日志
3. **数据验证**：每次测试后可以到数据库验证数据
4. **截图记录**：建议对前端界面进行截图记录
5. **性能监控**：关注API响应时间和并发性能

完成所有测试后，系统即验证完毕，可以正式使用！