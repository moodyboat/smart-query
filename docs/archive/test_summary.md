# 对话摘要功能测试指南

## 📋 测试目标

验证 PROMPT-3 的实现效果：
1. ✅ Token 预算控制（20000 token）替代硬编码40条
2. ✅ 保留最近10条完整消息 + 早期消息摘要
3. ✅ 摘要由 LLM 生成，保留关键决策
4. ✅ 50轮对话后，LLM 仍能回忆第3轮讨论的表名和列名

## 🧪 测试方法

### 方法1：通过 Web UI 测试（推荐）

1. **打开前端界面**
   ```bash
   # 访问 http://localhost:5173
   ```

2. **创建测试对话并进行50轮问答**

   模拟以下对话场景：
   ```
   第1-5轮：讨论 loan 表结构
   - "loan 表有哪些列？"
   - "loan_amount 列的数据类型是什么？"
   - "loan 表中有多少条记录？"
   - "查看 loan 表的前10条数据"
   - "loan 表中有哪些不同的 collateral_type？"

   第6-10轮：特征工程讨论
   - "用 loan 表创建一个违约预测模型"
   - "特征应该包括哪些列？"
   - "如何处理 credit_score 的缺失值？"
   - "使用 XGBoost 算法训练模型"
   - "设置 n_estimators=200, max_depth=6"

   第11-20轮：中间结果讨论
   - "查看模型训练结果"
   - "特征重要性如何？"
   - "哪个特征最重要？"

   第21-50轮：大量无关对话（填充token）
   - 重复查询其他表
   - 生成多个图表
   - 训练其他模型

   第51轮：验证测试
   - "还记得我们最开始讨论的 loan 表吗？"
   - "loan_amount 列是什么类型的？"
   - "我们在第3轮讨论的 collateral_type 有哪些值？"
   ```

3. **观察日志输出**
   ```bash
   # 实时查看摘要生成日志
   tail -f logs/backend.log | grep -E "\[SUMMARY\]|\[QUERY\]"
   ```

### 方法2：通过 API 直接测试

#### 测试脚本

```python
import requests
import time

BASE_URL = "http://localhost:8080/api/v1"

# 1. 创建新会话
conv = requests.post(f"{BASE_URL}/conversations").json()
conversation_id = conv["data"]["id"]
print(f"会话ID: {conversation_id}")

# 2. 模拟50轮对话
test_questions = [
    # 早期关键对话（需要被摘要记住）
    "loan 表有哪些列？包括列名和数据类型",
    "loan_amount 列的最大值和最小值是多少？",
    "loan 表中有几种不同的 collateral_type？分别是什么？",
    "请统计 loan 表中每个 collateral_type 的记录数",
    "查看 loan 表中 interest_rate 的分布情况",

    # 特征工程讨论
    "我想用 loan 表训练一个违约预测模型",
    "特征应该包括 loan_amount, interest_rate, credit_score",
    "使用 XGBoost 算法，参数设置 n_estimators=200",
    "开始训练模型，目标列是 default_label",

    # 中期对话
    "查看模型训练结果和准确率",
    "显示特征重要性排序",
    "credit_score 的重要性是多少？",

    # 填充对话（40轮无关问题，触发摘要）
    *["SELECT COUNT(*) FROM loan"] * 20,
    *["显示当前时间"] * 10,
    *["生成一个随机图表"] * 10,
]

# 3. 执行查询（注意需要SSE流式处理）
print("\n开始执行测试查询...")
for i, question in enumerate(test_questions[:50], 1):
    print(f"\n[第 {i} 轮] {question}")
    # 这里需要实现 SSE 消费逻辑
    # 实际测试建议用 Web UI
    time.sleep(0.5)

# 4. 验证测试 - 检查是否记得早期对话
print("\n=== 验证测试 ===")
verification_questions = [
    "还记得我们在第3轮讨论的 collateral_type 吗？",
    "loan_amount 列在第2轮查询中的最大值是多少？",
    "我们最早讨论的 loan 表有哪些列？"
]

for q in verification_questions:
    print(f"\n验证问题: {q}")
```

### 方法3：单元测试（最准确）

创建测试文件：
```bash
# 创建测试文件
touch backend/src/test/java/com/smartquery/service/ConversationSummaryServiceTest.java
```

### 方法4：数据库直接验证

```bash
# 1. 插入测试数据（模拟50轮对话）
mysql -uroot -p900110 smart_query << EOF
INSERT INTO sq_chat_message (conversation_id, role, content, created_at)
SELECT
  1,
  'user',
  CONCAT('测试消息 ', seq, '：loan表有loan_amount, interest_rate等列'),
  DATE_SUB(NOW(), INTERVAL seq MINUTE)
FROM (
  SELECT @row:=@row+1 AS seq
  FROM (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) t1,
       (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) t2,
       (SELECT @row:=0) t
  LIMIT 50
) seq_table;
EOF

# 2. 触发查询
curl -X POST "http://localhost:8080/api/v1/chat?conversationId=1" \
  -H "Content-Type: application/json" \
  -d '{"message":"还记得最早的loan表有哪些列吗？","dataSourceId":2}'

# 3. 查看日志
tail -f logs/backend.log | grep -A5 "\[SUMMARY\]"
```

## 🔍 观察要点

### 1. 摘要生成日志
```
[SUMMARY] Token budget exceeded: ~25000 tokens > 20000, messages: 52
[SUMMARY] Generating summary for conversation 1 with 42 messages using model glm-4
[SUMMARY] Generated summary (850 chars) for conversation 1
[SUMMARY] Replaced 42 early messages with summary (850 chars)
```

### 2. 摘要内容（示例）
```json
{
  "role": "system",
  "content": "[对话摘要] 以下是基于之前 42 条消息的摘要：\n\n讨论的表和列：\n- loan表：包含loan_amount（贷款金额）、interest_rate（利率）、credit_score（信用评分）、collateral_type（抵押类型）、default_label（违约标签）等列\n\ncollateral_type的值包括：房产、车辆、无抵押\n\n关键决策：\n- 使用XGBoost算法训练违约预测模型\n- 特征包括loan_amount, interest_rate, credit_score\n- 参数：n_estimators=200, max_depth=6\n\n模型结果：\n- 训练准确率95%\n- credit_score是最重要特征（0.33）\n\n你可以参考这些历史信息，但优先关注最近的消息。"
}
```

### 3. API 响应验证
```bash
# 查看摘要统计
curl http://localhost:8080/api/v1/admin/summary/stats
# 期望：cacheSize > 0 说明摘要已缓存

# 清除缓存重新测试
curl -X POST http://localhost:8080/api/v1/admin/summary/cache/evict/1
```

## ✅ 验证标准

| 验证项 | 预期结果 | 实际结果 |
|--------|----------|----------|
| Token预算控制 | 超过20000 token时触发摘要 | ⬜ |
| 摘要生成 | LLM生成有意义的摘要 | ⬜ |
| 保留最近消息 | 最近10条完整保留 | ⬜ |
| 早期信息回忆 | 第51轮能回忆第3轮的collateral_type | ⬜ |
| 缓存机制 | 30分钟内重复请求使用缓存 | ⬜ |

## 🐛 故障排查

### 问题1：摘要未生成
```bash
# 检查配置
grep "summary:" backend/src/main/resources/application.yml

# 检查日志
tail logs/backend.log | grep "\[SUMMARY\]"

# 手动触发
curl -X POST http://localhost:8080/api/v1/admin/summary/cache/evict-expired
```

### 问题2：LLM回忆失败
```bash
# 查看实际传递给LLM的context
tail logs/backend.log | grep -B10 "ReAct loop"

# 检查摘要质量
# 查看生成的摘要是否包含足够信息
```

### 问题3：性能问题
```bash
# 监控摘要生成耗时
grep "Generating summary" logs/backend.log -A2

# 调整缓存时间
vim backend/src/main/resources/application.yml
# 修改 cache-ttl-minutes: 30
```

## 📊 性能指标

| 指标 | 目标值 | 实际值 |
|------|--------|--------|
| 摘要生成时间 | < 5秒 | ___ |
| Token节省 | > 60% | ___ |
| 早期信息准确率 | > 80% | ___ |
| 缓存命中率 | > 50% | ___ |

## 🎯 快速测试（5分钟）

```bash
# 1. 打开浏览器访问前端
open http://localhost:5173

# 2. 在一个会话中连续提问50次
# 前几次问题：loan表结构、特征工程
# 中间40次：随便问（填充token）
# 最后问：还记得最开始讨论的loan表吗？

# 3. 观察后台日志
tail -f logs/backend.log | grep "\[SUMMARY\]"

# 4. 验证LLM能否回答关于早期对话的问题
```
