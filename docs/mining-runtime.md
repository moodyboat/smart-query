# 建模运行时架构

建模、流水线执行和预测统一使用 `backend/src/main/resources/python/mining_runtime.py`。
Java 不再拼接 Python 源码，只负责：

1. 鉴权、资源归属和任务状态；
2. 校验表名、字段名、筛选条件和自定义算法模板；
3. 写入带 `protocolVersion` 的 JSON 请求文件；
4. 启动固定 Python 运行器并读取 JSON 结果文件；
5. 将 stdout/stderr 作为日志保存，不从日志中解析业务结果。

## 训练与预测一致性

模型制品是一个 joblib bundle，其中的 `pipeline` 是完整 sklearn `Pipeline`：

```text
原始字段（`drop` 行过滤属于切分前的数据集选择）
  -> 在训练集拟合的缺失值填充策略
  -> 日期/对数/分箱/交互/多项式/频率编码/目标编码
  -> 类别编码
  -> 缩放
  -> estimator
```

训练测试切分发生在任何需要学习数据分布的预处理之前。留出集评估使用仅在训练集
拟合的 Pipeline；交叉验证会 clone Pipeline，使每一折分别拟合预处理器；最终发布制品
在评估完成后独立使用全量数据重训。预测直接调用制品内同一个 Pipeline，不再维护另一套
预处理代码。

## 自定义算法模板契约

模板可使用 `params`、`X`、`y`、`df`、`_model_type`，但职责仅限导入算法并构造
sklearn 兼容的 `clf`。模板不得执行 `fit`、缺失值处理、编码、缩放或特征工程。
自定义算法写操作仅允许管理员；运行前仍经过静态安全检查，生产环境应继续使用受限容器。

## 协议

- 当前协议版本：`1`
- 请求和响应：临时 JSON 文件
- 训练进度：独立原子更新的 `progress.json`，包含 executionId、阶段、百分比和消息
- 响应写入：临时文件写完并 `fsync` 后原子替换
- stdout/stderr：仅日志
- 模型制品架构版本：`3`
- 模型制品：按模型版本/执行唯一命名，包含完整 Pipeline、训练执行ID、概率校准器、决策阈值、监控基线、sklearn 版本和 SHA-256 校验值
- 旧版仅保存 estimator 的 `.pkl` 不再兼容预测，需要重新训练生成 Pipeline 制品

## 异步训练

用户或 Agent 调用训练接口后立即得到 `executionId`。后台任务使用提交时捕获的用户身份，
并在执行前再次检查模型和数据源权限。`sq_model_execution` 保存 queued/running/success/
failed/canceled 状态、真实阶段、百分比、日志尾部、开始结束时间和制品信息。

查询与控制接口：

- `POST /api/v1/mining/model/{modelId}/train`：提交训练；
- `GET /api/v1/mining/model/{modelId}/executions/{executionId}`：查询状态；
- `POST /api/v1/mining/model/{modelId}/executions/{executionId}/cancel`：取消；
- `GET /api/v1/mining/model/{modelId}/train-stream?executionId=...`：SSE 实时进度和日志；
- `GET /api/v1/mining/model/{modelId}/artifact-status`：检查旧制品；
- `POST /api/v1/mining/model/{modelId}/artifact-migration`：按原配置异步重训。

SSE 使用带 JWT 请求头的 fetch 流。任务事件持久化在 `sq_task_event`，带事件 ID、心跳和
`Last-Event-ID` 续传；后端使用统一事件总线推送，连接本身不轮询模型表，也不占用独立休眠线程。

## 评估与治理

- 分类同时输出 Balanced Accuracy、Macro/逐类指标、风险类 Precision/Recall、PR-AUC、ROC-AUC、KS、Lift 和 Brier Score；
- `groupColumns` 使用 Group Split/Group CV，避免企业、客户或合同跨训练测试集；
- `oos` 必须指定不同的独立数据表，并保存样本快照 SHA-256；
- `temporal` 使用多个 walk-forward 窗口并保存每期、均值、波动、最差值和最近值；
- 校准支持 sigmoid/Platt 与 isotonic，阈值支持固定、最大 F1、最低召回和最小误报/漏报成本；
- 发布前统一执行治理策略，`force` 不能绕过质量硬失败；策略可要求管理员审批；
- 训练制品保存特征/分数分布基线，已发布模型定时计算 PSI、缺失率变化和分数漂移。
