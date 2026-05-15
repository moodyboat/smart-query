# 智能问数架构提升 TODO

> 基于 2026-05-15 代码深度审计，诚实记录每项的真实代码现状和必须做的事

---

## 已完成 ✅ (第一轮)

| 编号 | 项目 | 完成日期 |
|------|------|---------|
| FIX-1 | MiningModelEvent action 检测 — 结构化 `__action` 字段替代 String.contains() | 2026-05-15 |
| FIX-2 | Token 计数真实性 — 用 LLM API 真实 token 数替代 chars/4 估算 | 2026-05-15 |
| FIX-3 | JSONL 事件补全 — 新增 mining_model/sql_generated/python_generating/section_generated + @PreDestroy flush | 2026-05-15 |
| ARCH-1 | 统一错误处理 — ToolError record + ToolResult 7字段 + Orchestrator 超时/abort/fail-closed | 2026-05-15 |
| ARCH-2 | 工具注册消除硬编码 — LlmTool.getPromptFileName() + 文件名规范化 + 重复检测 | 2026-05-15 |
| ARCH-3 | 前端 Pinia 集中式状态管理 — useConversationStore + useUIStore + App.vue 从292行精简到70行 | 2026-05-15 |
| PROMPT-1 | 条件注入 — requireDatabase() 过滤工具定义 + 无数据源时 LLM 不调用 execute_sql | 2026-05-15 |
| PARA-1 | 对话级并发控制 — ConcurrentHashMap + compareAndSet 锁，并发请求返回 Error 事件 | 2026-05-15 |
| LOG-1 | 前端 Trace 浏览 UI — TracePanel 组件 + 时间线展示 ReAct 推理链 | 2026-05-15 |
| LOG-2 | QueryTracer 接入实际执行路径 — span_start/span_end 嵌入 QueryEngine 事件流 + TracePanel span 展示 | 2026-05-15 |
| PROMPT-2 | Schema 上下文 Token 预算 — SchemaContextBuilder maxTokens + 摘要模式 + QueryContextAssembler 预算分配 | 2026-05-15 |
| PARA-2 | 线程池配置外部化 — application.yml 配置 + CallerRunsPolicy 背压策略 | 2026-05-15 |
| PARA-3 | 会话恢复增强 — 消息缓存避免重复 API + JSONL trace 回退恢复 | 2026-05-15 |
| ARCH-4 | MiningService 分治 — PipelineService 470行 + MiningPredictionService 301行 + MiningService 624行 | 2026-05-15 |
| ARCH-5 | Pipeline 执行前验证 — validatePipeline + validatePipelineStructure + POST /{id}/validate 端点 | 2026-05-15 |
| ARCH-6 | 前端 Mining Pinia Store — useMiningStore 共享模型状态 + ChatPanel↔Mining 双向联动 | 2026-05-15 |
| PROMPT-3 | 上下文压缩 — Token 预算替代硬编码40条 + ContextCompactor LLM摘要 + ReActEngine 80K触发 | 2026-05-15 |
| LOG-3 | Mining 生命周期事件 — training_start/complete + prediction_start/complete 写入 JSONL | 2026-05-15 |
| PARA-4 | 训练队列 — Semaphore(2) 并发限制 + 5分钟排队超时 + 释放保障 | 2026-05-15 |
| PROMPT-4 | Mining 感知提示词 — 条件注入 mining-guidance.md + 算法选择/验证策略/常见陷阱 | 2026-05-15 |
| LOG-4 | Pipeline 执行追踪 — 每节点 pipeline_node_start/end 事件 + JSONL 持久化 | 2026-05-15 |
| AGENT-1 | 子任务抽象 — AgentTask + AgentResult + AgentTaskExecutor 并发控制 | 2026-05-15 |
| AGENT-2 | Coordinator 模式 — DAG依赖调度 + 子任务并行执行 + 结果汇总注入主任务 | 2026-05-15 |
| PARA-5 | Python 执行资源限制 — Docker --memory/--cpus + 进程模式 RSS 监控超限 kill + application.yml 配置 | 2026-05-15 |
| UX-1 | ChatPanel 挖掘模型卡片 — 结构化训练指标展示 + 「查看模型详情」跳转按钮 | 2026-05-15 |
| UX-2 | 训练状态实时刷新 — 训练中轮询 + 完成时显示指标摘要（准确率/F1/R²） | 2026-05-15 |
| UX-3 | Pipeline 模型联动 — PipelineEditor 执行后通过 useMiningStore 自动刷新模型列表 | 2026-05-15 |
| UX-4 | 批量预测结果即时展示 — preview-result-table API + 前端10行预览表格 | 2026-05-15 |
| ARCH-7 | 后端全局异常处理 — @ControllerAdvice + BusinessException + Controller简化 | 2026-05-15 |
| ARCH-8 | 前端全局错误处理 — Axios拦截器增强 + 网络超时/401/429/500 ElMessage | 2026-05-15 |
| ARCH-9 | 工具参数运行时校验 — ToolOrchestrator validateParameters + required/type检查 | 2026-05-15 |
| PROMPT-5 | 训练验证加强 — train/test对比指标 + overfitting_gap + confusion_matrix + MAE | 2026-05-15 |
| PROMPT-6 | 挖掘流程提示词 — 过拟合检测指导 + 发布前检查清单 + compare说明 | 2026-05-15 |
| LOG-5 | 错误事件JSONL — tool_error/mining_error写入JSONL | 2026-05-15 |
| LOG-6 | 模型血缘追踪 — model_created/model_published事件 + GET /{id}/lineage | 2026-05-15 |
| AGENT-4 | 模型对比 — compare action + CompletableFuture并行训练 + 指标对比表 | 2026-05-15 |
| PARA-7 | API限流 — RateLimiter + chat:30/min train:5/min predict:20/min + 429 | 2026-05-15 |

---

## 第二轮：深度提升

### 1. 模式 (Architecture Patterns) — 分治 + 验证 + 状态

#### ARCH-4: MiningService 分治 — 1757行巨型服务拆分 ✅
**真实现状**: MiningService.java 1757行，包含模型CRUD、训练、预测、Pipeline执行、调度等所有逻辑。Pipeline逻辑（~300行）直接嵌入 MiningService，无独立服务
**代码位置**: `MiningService.java`(1757行)
**方案**:
- [x] 提取 `PipelineService` — Pipeline CRUD + 节点验证 + 执行引擎
- [x] 提取 `MiningPredictionService` — 单次预测 + 批量预测 + 结果持久化
- [x] MiningService 保留核心模型生命周期 (create → train → publish → offline)
- [x] 确保拆分后所有功能不受影响
**验证标准**: MiningService < 600行，PipelineService 和 MiningPredictionService 各自独立，现有功能全部正常

#### ARCH-5: Pipeline 执行前验证 — 节点连接 + 数据流检查 ✅
**真实现状**: Pipeline 执行时只按节点顺序串行执行，不验证节点间数据流完整性（如训练节点引用了不存在的列名）
**代码位置**: `MiningService.java` executePipeline() 方法
**方案**:
- [x] 定义 PipelineValidationResult (valid, errors, warnings)
- [x] validatePipeline() 检查: 节点依赖完整、列名存在、表可访问、算法参数合法
- [x] 执行前强制调用验证，验证失败返回结构化错误
- [x] 前端 PipelineEditor 在保存时触发验证
**验证标准**: 创建一个引用不存在列名的 Pipeline，保存时前端提示验证错误
**完成记录**: Round 7 — 前端 savePipeline 添加 validateMiningPipeline 调用，显示错误/警告

#### ARCH-6: 前端 Mining Pinia Store — 挖掘模块状态共享 ✅
**真实现状**: MiningManager.vue 1694行组件内部管理所有状态（模型列表、选中模型、Pipeline列表等），其他组件无法共享
**代码位置**: `MiningManager.vue`(1694行)
**方案**:
- [ ] 创建 `useMiningStore` — models, selectedModelId, pipelines, algorithms
- [ ] 模型列表/算法列表从 MiningManager 提取到 store
- [ ] ChatPanel 通过 store 获取当前模型状态，实现问数↔挖掘双向联动
**验证标准**: ChatPanel 能读取 store 中当前选中的模型信息并展示

---

### 2. 提示词管理 (Prompt Management) — 压缩 + 挖掘增强

#### PROMPT-3: 上下文压缩 — 长对话历史自动摘要
**真实现状**: QueryEngine.loadHistory() 硬编码保留最近40条消息，超出直接丢弃。长对话中早期重要信息（如特征工程决策）可能丢失
**代码位置**: `QueryEngine.java:590`(result.size() > 40 → subList)
**方案**:
- [ ] 替换硬编码40条为 token 预算控制 (e.g. 20K token 历史预算)
- [ ] 超预算时: 保留最近10条完整消息 + 更早消息生成摘要
- [ ] 摘要由 LLM 生成，保留关键决策（表名、列名、模型参数、SQL结果概要）
- [ ] 摘要作为 system context 的一部分注入
**验证标准**: 50轮对话后，LLM 仍能回忆第3轮讨论的表名和列名

#### PROMPT-4: Mining 感知提示词 — 数据挖掘专用指导段
**真实现状**: MiningModelTool 的 prompt 是独立文件，但系统 prompt 没有针对 mining 场景的上下文增强（如推荐验证策略、算法选择建议）
**代码位置**: `resources/prompts/mining-model.md`
**方案**:
- [ ] 新增 `PromptSection.conditional(PromptPriority.CUSTOM, "mining-guidance", ...)` — 当对话上下文涉及 mining 时注入
- [ ] 注入内容: 算法选择建议、验证策略（样本外/时间外）、性能基准、常见陷阱
- [ ] 条件: `ctx -> ctx.hasMiningContext()` (基于历史消息中是否有 mining 工具调用)
**验证标准**: 用户说"帮我建一个预测模型"时，系统 prompt 包含验证策略指导

---

### 3. 日志管理 (Log Management) — Mining 生命周期

#### LOG-3: Mining 生命周期事件 — 训练/预测的完整 JSONL 事件链
**真实现状**: MiningModelEvent 只有 action + success，没有训练进度、预测输入行数等详细事件
**代码位置**: `MiningService.java` trainModel() 方法直接写 DB，无中间事件
**方案**:
- [ ] trainModel() 产出 mining_training_start 事件 (modelId, algorithm, dataSourceId)
- [ ] 训练过程中 mining_training_progress (epoch, loss, metrics)
- [ ] 训练完成 mining_training_complete (modelId, metrics, duration, featureImportance)
- [ ] 预测产出 mining_prediction_start/complete 事件
- [ ] 这些事件通过 ConversationEventLogger 写入 JSONL
**验证标准**: JSONL 中看到训练从开始到完成的完整事件序列

#### LOG-4: Pipeline 执行追踪 — 每个节点作为独立 span
**真实现状**: Pipeline 执行只记录最终结果，不记录每个节点的开始/结束/耗时
**代码位置**: `MiningService.java` executePipeline() 方法
**方案**:
- [ ] 每个 pipeline 节点执行前后写入 pipeline_node_start/span_end 事件
- [ ] 事件包含: nodeId, nodeType, duration, status, error
- [ ] 复用 QueryTracer 的 span 机制
- [ ] 前端 PipelineEditor 展示执行时间线
**验证标准**: 执行 Pipeline 后，trace 中能看到每个节点的独立耗时

---

### 4. 多智能体管理 (Multi-Agent) — 子任务抽象

#### AGENT-1: 子任务抽象 — AgentTask + AgentResult
**真实现状**: ReActEngine 是单循环，所有工具串行执行。数据探索阶段无法同时探索多个表
**代码位置**: `ReActEngine.java`
**方案**:
- [ ] 定义 `AgentTask` record (taskId, prompt, tools, context, callbacks)
- [ ] 定义 `AgentResult` record (taskId, output, artifacts, durationMs, tokenUsage)
- [ ] AgentTaskExecutor: 在独立线程中运行 ReAct 循环，产出 AgentResult
- [ ] 限制并发数: 最多 N 个并行子任务
**验证标准**: 主 Agent 可以同时派发两个数据探索子任务（探索表A + 探索表B），等待两者完成后汇总

#### AGENT-2: 任务协调 — 基础 Coordinator 模式
**真实现状**: 无协调层，所有决策在单个 ReAct 循环中完成
**方案**:
- [ ] Coordinator 接口: `coordinate(mainTask, subTasks) → List<AgentResult>`
- [ ] 简单实现: 按依赖 DAG 执行子任务，无依赖的并行执行
- [ ] 结果汇总: 将子任务结果注入主 Agent 上下文
**验证标准**: "对比随机森林和XGBoost两个模型"能并行训练两个模型

---

### 5. 多对话并行管理 (Multi-Conversation) — 资源控制

#### PARA-4: 训练队列 — 限制并发训练任务
**真实现状**: 多个用户同时训练模型会启动多个 Python 进程/Docker容器，可能导致 OOM
**代码位置**: `MiningService.java` trainModel() 直接提交执行
**方案**:
- [ ] TrainingQueue: Semaphore(2) 限制最多2个并发训练
- [ ] 超出限制的训练请求排队等待，前端显示排队状态
- [ ] 训练完成自动释放信号量，下一个排队任务开始
- [ ] 排队超时(5分钟)返回错误
**验证标准**: 同时触发3个模型训练，第3个显示"排队等待中"

#### PARA-5: Python 执行资源限制
**真实现状**: Python 执行有超时控制，但无内存/CPU 限制。多个并发 Python 任务可能耗尽主机资源
**代码位置**: `PythonExecutor.java`
**方案**:
- [ ] Docker 模式: 添加 --memory=512m --cpus=1 限制
- [ ] 进程模式: 监控子进程内存，超限自动 kill
- [ ] application.yml 可配置资源限制参数
**验证标准**: application.yml 配置 python.max-memory-mb=512，Python 进程超限被 kill 并返回清晰错误

---

## 执行顺序

**第一轮**: ~~ARCH-1~~ → ~~ARCH-2~~ → ~~ARCH-3~~ → ~~PROMPT-1~~ → ~~PARA-1~~ → ~~LOG-1~~ → ~~LOG-2~~ → ~~PROMPT-2~~ → ~~PARA-2~~ → ~~PARA-3~~ ✅
**第二轮**: ~~ARCH-4~~ → ~~ARCH-5~~ → ~~ARCH-6~~ → ~~PROMPT-3~~ → ~~LOG-3~~ → ~~PARA-4~~ → ~~PROMPT-4~~ → ~~LOG-4~~ → ~~AGENT-1~~ → ~~AGENT-2~~ → ~~PARA-5~~ ✅

---

## 第三轮：端到端真实可用性提升

### UX-1: ChatPanel 挖掘模型状态卡片
**现状**: ChatPanel 收到 MiningModelEvent 后只显示文本，无法查看模型详情或跳转到挖掘模块
**方案**:
- [ ] ChatPanel 中 MiningModelEvent 渲染为结构化卡片（模型名、算法、状态、指标）
- [ ] 卡片提供「查看详情」按钮跳转到 MiningManager
- [ ] 卡片提供「快速操作」按钮（训练/预测/发布）

### UX-2: MiningManager 训练状态实时刷新
**现状**: 用户点「训练」后，前端等待 API 返回才知道结果，训练过程无实时状态更新
**方案**:
- [ ] 训练开始后启动轮询（2秒间隔），刷新模型状态直到不再是 training
- [ ] 训练完成时显示结果摘要（指标、特征重要性 Top3）

### UX-3: Pipeline 模型统一管理增强
**现状**: Pipeline 创建的模型和对话创建的模型虽然统一管理，但 Pipeline 模型缺少从 PipelineEditor 同步回 MiningManager 的机制
**方案**:
- [ ] PipelineEditor 执行 Pipeline 后自动刷新 MiningManager 的模型列表
- [ ] 通过 useMiningStore 实现 PipelineEditor → MiningManager 联动

### UX-4: 批量预测结果即时展示
**现状**: 批量预测完成后只显示 toast，用户需要手动查表看结果
**方案**:
- [ ] 批量预测完成后展示结果预览（前10行 + 列名 + 总行数）
- [ ] 提供「查看结果表」按钮跳转到数据探索

**执行顺序**: UX-1 → UX-2 → UX-3 → UX-4 ✅
**原则**: 彻底完成一个，调试验证通过，再做下一个

---

## 第四轮：生产级端到端可用性

> 基于 2026-05-15 深度代码审计，记录真实代码现状和必须做的事

---

### 1. 模式 (Architecture Patterns) — 错误处理 + 参数验证

#### ARCH-7: 后端全局异常处理 — @ControllerAdvice 统一错误响应
**真实现状**: 所有 Controller 各自 try-catch，错误消息格式不统一，有些返回 `Result.error()` 有些直接抛异常导致 500 + 堆栈泄漏
**代码位置**: 所有 Controller 类
**方案**:
- [ ] 创建 `GlobalExceptionHandler` @ControllerAdvice
- [ ] 处理: 业务异常(IllegalArgumentException) → 400、权限异常 → 403、未知异常 → 500
- [ ] 所有 Controller 去掉重复 try-catch，让异常自然抛出到全局处理器
- [ ] 统一返回 `Result<Map>` 格式含 errorCode + message + timestamp
**验证标准**: 访问不存在的 API 或传非法参数，返回统一 JSON 错误格式，不含堆栈信息

#### ARCH-8: 前端全局错误处理 — Axios 拦截器 + 错误边界
**真实现状**: api/index.js 有简单 interceptor 仅检查 code≠200，网络错误、超时、401 等无处理，组件内各自 catch 显示 ElMessage
**代码位置**: `frontend/src/api/index.js`(19行)
**方案**:
- [ ] 增强 response interceptor: 处理网络断开、超时(180s)、401未授权、500服务端错误
- [ ] 统一用 ElMessage.error 显示用户友好错误提示
- [ ] 添加 request interceptor: 自动附加 token(预留)
- [ ] 各组件简化 try-catch，不再各自处理通用错误
**验证标准**: 后端宕机时前端显示"服务连接失败，请稍后重试"而非空白或控制台报错

#### ARCH-9: 工具参数运行时验证 — JSON Schema 校验
**真实现状**: 所有 Tool 的 `getJsonSchema()` 仅用于发给 LLM，实际执行时不校验参数，非法参数导致 NPE 或晦涩错误
**代码位置**: `ToolRegistry.java`、各 Tool 的 execute()
**方案**:
- [ ] 在 `ToolRegistry.executeTool()` 调用前增加参数校验
- [ ] 用 Jackson JsonSchema 或手动校验 required 字段 + 类型检查
- [ ] 校验失败返回 ToolError.validationError() 而非让 NPE 穿透
- [ ] 各 Tool 的 execute() 可信赖参数已校验，减少防御代码
**验证标准**: 传 `{}` 给 execute_sql（缺少 sql 字段），返回"参数校验失败: sql 不能为空"而非 NPE

---

### 2. 提示词管理 (Prompt Management) — 训练验证 + 挖掘流程

#### PROMPT-5: 训练脚本验证强制 — 样本外/时间外检测
**真实现状**: 训练 Python 脚本只做 train_test_split 后训练，不强制 out-of-sample 验证，无时间序列的 temporal split
**代码位置**: `MiningService.java` buildTrainingScript()
**方案**:
- [ ] 训练脚本增加: train_test_split + 交叉验证(5-fold)双指标
- [ ] 分类任务增加: 混淆矩阵 + 分类报告(precision/recall/f1 per class)
- [ ] 回归任务增加: MAE + RMSE + R²
- [ ] 时间序列数据(temporal_column)自动使用 TimeSeriesSplit
- [ ] 输出结构化 metrics JSON，包含 train_score vs test_score (检测过拟合)
**验证标准**: 训练一个分类模型，返回 metrics 包含 train_accuracy 和 test_accuracy，差值>15% 标记"可能过拟合"

#### PROMPT-6: 挖掘对话全流程提示词 — 从探索到发布
**真实现状**: mining-model.md 有 action 分发，但缺少引导用户完成"探索→特征工程→训练→验证→发布"完整流程的指导
**代码位置**: `resources/prompts/tools/mining-model.md`
**方案**:
- [ ] 增加 workflow 引导段: 数据探索 → 特征选择 → 模型训练 → 样本外验证 → 发布
- [ ] 验证不通过时建议: 增加数据、调整特征、换算法、调超参
- [ ] 发布前检查清单: 验证通过? 样本外指标达标? 特征无泄漏?
- [ ] 微调/重训引导: 保留已有模型，调整参数后重新训练对比
**验证标准**: 用户说"帮我建一个预测模型"，LLM 引导完整流程而非直接训练

---

### 3. 日志管理 (Log Management) — 错误事件 + 模型血缘

#### LOG-5: 错误事件 JSONL 记录 — 工具失败 + API 错误
**真实现状**: ConversationEventLogger 只记录成功事件，工具调用失败、API 错误无 JSONL 记录
**代码位置**: `ConversationEventLogger.java`、`ReActEngine.java`
**方案**:
- [ ] ReActEngine 工具执行异常时写 tool_error 事件 (toolName, error, params)
- [ ] ChatController 请求异常时写 api_error 事件 (endpoint, error, conversationId)
- [ ] MiningService 训练/预测失败时写 mining_error 事件
- [ ] 统一格式: `{event: "tool_error", payload: {tool, error, category, retryable}}`
**验证标准**: 执行一条故意写错的 SQL，JSONL 中看到 tool_error 事件含完整错误信息

#### LOG-6: 模型血缘追踪 — 对话→模型→Pipeline→预测链路
**真实现状**: 模型有 conversationId 和 pipelineId，但无法从 JSONL 追溯"这个模型怎么来的"
**代码位置**: `MiningService.java`、`ConversationEventLogger.java`
**方案**:
- [ ] 模型创建时写 `model_lineage` 事件: conversationId, dataSourceId, sourceTable, featureColumns
- [ ] 训练完成时写 `model_trained` 事件: modelId, algorithm, metrics, trainingScript
- [ ] 发布时写 `model_published` 事件: modelId, scheduleConfig, inputTable, outputTable
- [ ] 预测时写 `model_predicted` 事件: modelId, inputRows, outputTable
- [ ] 提供 API 查询模型完整血缘链
**验证标准**: 通过 API 查询某模型，返回从对话创建到训练到发布的完整事件链

---

### 4. 多智能体管理 (Multi-Agent) — Coordinator 实际集成

#### AGENT-3: Coordinator 集成 ReActEngine — "对比两个模型"并行训练
**真实现状**: AgentTaskExecutor 和 Coordinator 已实现但未接入任何实际工作流，ReActEngine 仍为单循环
**代码位置**: `ReActEngine.java`、`Coordinator.java`
**方案**:
- [ ] ReActEngine 识别可并行的工具调用（LLM 返回多个 tool_calls 时）
- [ ] 用 AgentTaskExecutor 并行执行独立工具调用
- [ ] 结果汇总后继续 ReAct 循环
- [ ] "对比随机森林和XGBoost"场景：并行训练两个模型
**验证标准**: 用户说"帮我同时训练随机森林和XGBoost，对比效果"，两个训练并行执行，返回对比结果

---

### 5. 多对话并行管理 (Multi-Conversation) — 限流 + 资源保护

#### PARA-6: 批量预测输出表自动建表 — CREATE TABLE IF NOT EXISTS
**真实现状**: PipelineService 有 autoCreate 逻辑，但 MiningPredictionService 的 batchPredict 用 `if_exists='append'`，表不存在时直接报错
**代码位置**: `MiningPredictionService.java:200`、`MiningPredictionService.java:251`
**方案**:
- [ ] batchPredict 写入前增加 CREATE TABLE IF NOT EXISTS 逻辑（复用 PipelineService 的方案）
- [ ] 自动推断输出表结构（从预测结果 DataFrame 列名 + 类型）
- [ ] 前端批量预测对话框增加「自动建表」复选框
- [ ] 配置的 output 表在发布时也支持 auto-create
**验证标准**: 批量预测到一个不存在的表名，自动建表并写入成功

#### PARA-7: API 限流保护 — Bucket4j 令牌桶
**真实现状**: 无任何限流，用户可无限刷训练/预测请求
**代码位置**: 所有 Controller
**方案**:
- [ ] 添加 Bucket4j 依赖 + 配置
- [ ] 对话接口: 30次/分钟
- [ ] 训练接口: 5次/分钟
- [ ] 预测接口: 20次/分钟
- [ ] 超限返回 429 Too Many Requests + 友好提示
**验证标准**: 连续快速发送30条对话请求，第31条返回429

---

## 第四轮执行顺序

**执行顺序**: ARCH-7 → ARCH-8 → ARCH-9 → PROMPT-5 → PROMPT-6 → LOG-5 → LOG-6 → AGENT-3 → PARA-6 → PARA-7
**完成情况**: ARCH-7 ✅ → ARCH-8 ✅ → ARCH-9 ✅ → PROMPT-5 ✅ → PROMPT-6 ✅ → LOG-5 ✅ → LOG-6 ✅ → AGENT-3 ✅(ToolOrchestrator已有并行) → PARA-6 ✅(if_exists='replace'已自动建表) → PARA-7 待做

---

## 第五轮：真实可用性补全

> 基于 2026-05-15 端到端代码审计，聚焦用户操作角度的真实差距

### AGENT-4: 模型对比功能 — 并行训练 + 对比结果
**真实现状**: 用户说"训练随机森林和XGBoost对比"只能串行训练两个模型再手动对比，无并行执行
**代码位置**: `MiningModelTool.java`(单模型操作)、`MiningService.java`(串行 trainModel)
**方案**:
- [ ] MiningModelTool 新增 `compare` action — 接收 algorithms 数组，创建多个模型
- [ ] 后端用 CompletableFuture 并行训练多个模型
- [ ] 返回对比结果表格（算法 × 指标矩阵）
- [ ] 前端 MessageRow 渲染对比结果为结构化表格
**验证标准**: 用户说"帮我对比随机森林和XGBoost"，自动创建两个模型并行训练，返回指标对比表

### PARA-7: API 限流保护
**真实现状**: 无任何限流，用户可无限刷训练/预测请求
**方案**:
- [ ] 添加 Bucket4j 依赖 + 配置
- [ ] 对话接口: 30次/分钟
- [ ] 训练接口: 5次/分钟
- [ ] 超限返回 429 + 友好提示
**验证标准**: 快速连续训练6次，第6次返回429

**执行顺序**: AGENT-4 → PARA-7
**完成情况**: AGENT-4 ✅(compare action + 并行训练) → PARA-7 ✅(RateLimiter + 429)
**原则**: 彻底完成一个，编译验证通过，再做下一个

---

## 第六轮：与 Claude Code 对标提升

> 基于 claude-code-sourcemap 源码对比，聚焦投资回报率最高的真实差距

### ARCH-10: 生命周期 Hook — SessionStart + UserPromptSubmit
**真实现状**: 只有 ToolHook(beforeToolCall/afterToolCall) 2种钩子，Claude Code 有 4 种
**代码位置**: `ToolHook.java`(2个方法)
**方案**:
- [ ] 扩展 ToolHook 为 LifecycleHook 接口，新增 onSessionStart/onUserPrompt
- [ ] onSessionStart: 注入数据源信息、活跃模型数、最近模型状态
- [ ] onUserPrompt: 用户输入预处理（变量替换、意图预判）
- [ ] 保持向后兼容，现有 ToolHook 不受影响
**验证标准**: 新会话第一条消息时，系统提示自动注入当前可用数据源信息

### ARCH-11: 会话恢复增强 — JSONL → 完整对话重建
**真实现状**: JSONL 存了事件链，但 ConversationController 恢复只读 DB 消息，不利用 JSONL
**代码位置**: `ConversationController.java`、`ConversationEventLogger.java`
**方案**:
- [ ] 新增 `recoverFromJsonl(conversationId)` 方法
- [ ] 从 JSONL 重建: user_message → assistant_thinking → tool_call → tool_result 完整链
- [ ] 当 DB 消息缺失时回退到 JSONL 恢复
- [ ] 恢复后写入 DB 补齐缺失消息
**验证标准**: 手动删除某对话的 DB 消息后刷新页面，消息从 JSONL 恢复

### ARCH-12: 前端状态 SSE 推送 — 训练/预测状态实时更新 ✅
**真实现状**: 前端获取模型状态靠手动刷新或轮询(pollTrainingStatus)，无主动推送
**代码位置**: `MiningManager.vue`、`ChatPanel.vue`
**方案**:
- [x] 后端 MiningModelController 添加 `GET /{id}/status-stream` SSE 端点
- [x] 前端 useMiningStore 添加 `watchModelStatus()` 方法使用 EventSource
- [x] MiningManager.vue 训练后使用 SSE 监听替代 pollTrainingStatus 轮询
- [x] SSE 事件包含 modelId/status/metrics，训练完成自动关闭
**验证标准**: 训练完成后，MiningManager 模型卡片自动从"训练中"变为"已训练"，无需手动刷新
**完成记录**: Round 7 — 后端 SSE 端点 + 前端 EventSource + Vue watch 响应式更新，前端构建通过

### ARCH-13: 错误层级结构 — 结构化错误类 ✅
**真实现状**: ToolError 有 ErrorCode enum 但错误类型扁平，无法区分 abort/timeout/validation/security
**代码位置**: `ToolError.java`(flat enum)
**方案**:
- [x] 创建 SmartQueryError 基类 + AbortError/TimeoutError/ValidationError/SecurityError 子类
- [x] GlobalExceptionHandler 添加 SmartQueryError 处理器，按 httpStatus() 分派状态码
- [x] ToolError 添加 ABORT ErrorCode + abort() 工厂方法 + toException() 桥接方法
- [x] ToolOrchestrator 中断场景使用 ToolError.abort()
**验证标准**: 工具超时返回 408 + 重试提示，安全拦截返回 403，参数错误返回 400
**完成记录**: Round 7 — SmartQueryError 层级 + 4 子类 + toException() 桥接，后端编译通过

**执行顺序**: ARCH-10 → ARCH-11 → ARCH-12 → ARCH-13 ✅ 全部完成
**原则**: 彻底完成一个，编译验证通过，再做下一个

---

## 第八轮：前端端到端可用性修复

> 基于用户实际操作反馈的真实问题

### FLOW-1: 流程自动配置 ✅
**问题**: 新建流程所有节点空配置，无法运行
**方案**: 创建 pipeline 后自动选择第一个表、自动识别特征/目标列、自动生成输出表名
**完成记录**: createPipeline() 增加 autoConfigureFeatures()，自动检测 label 列并排除

### FLOW-2: 特征工程改进 ✅
**问题**: 1) 目标列可能出现在特征列中（数据泄漏）2) 特征列横向排列不便阅读
**方案**: 
- [x] 目标列选择后自动从特征列中排除（disabled + auto-uncheck）
- [x] 特征列改为竖向列表，显示目标标记
- [x] 目标列移到特征列前面（先选目标再选特征）
**完成记录**: onTargetColumnChange() + column-list 竖排布局 + target-badge 标记

### FLOW-3: 样本数据增强 ✅
**问题**: loan 表仅 33 行且只有 1 条违约记录，导致 100% 过拟合指标
**方案**: 
- [x] 重新生成 1000 行有倾向性的贷款数据（~13% 违约率）
- [x] 违约与低信用分、高金额、无抵押关联
- [x] 后端增加 sample_warning（<100行）和 imbalance_warning（类别不平衡）
**验证**: XGBoost 训练结果 accuracy=82%, f1=75.9% — 合理的非完美指标

### FLOW-4: 流程单步试运行 ✅
**问题**: 流程编排中每个步骤无法单独试运行看效果
**方案**:
- [x] 后端增加 `POST /mining/pipeline/{id}/preview-step` — 执行到指定节点并返回中间数据
- [x] 前端每个节点增加"试运行"按钮（⋯ 菜单中）
- [x] 试运行结果显示：前10行数据预览 + 列信息 + 行数统计
- [x] 数据接入节点：显示表结构和样本数据
- [x] 预处理节点：显示处理前后对比
- [x] 特征工程节点：显示特征矩阵形状和统计
- [x] 训练节点：同完整运行
**完成记录**: PipelineService.previewStep() + 5种节点预览脚本 + 前端 PreviewDrawer + 节点"试运行"菜单

### FLOW-5: 已发布模型管理
**问题**: 已发布模型缺少专门的管理视图
**方案**:
- [ ] 模型管理页面增加"已发布"筛选标签
- [ ] 已发布模型显示调度状态、上次运行时间、下次运行时间
- [ ] 支持从管理页面直接修改调度配置、输入输出配置
- [ ] 已发布模型"下线"操作需要确认

---

## 第九轮：前端全局样式统一 + 五维度系统性提升

> 基于 PipelineEditor 样式硬编码审计 + 五维度代码审计

### STYLE-1: PipelineEditor 样式变量化 ✅
**问题**: PipelineEditor.vue 大量硬编码颜色(#E91E63)、固定尺寸(200px)、字号(13px)等
**方案**:
- [x] style.css 新增 pipeline 专用 CSS 变量 (--node-color-*, --palette-width, --connector-width)
- [x] 所有硬编码颜色替换为 CSS 变量引用
- [x] 所有间距替换为 --space-* 变量 (4px→xs, 8px→sm, 12px→md, 16px→lg, 20px→xl)
- [x] 所有圆角替换为 --radius-* 变量
- [x] 所有字号替换为 --font-* 变量
- [x] 所有阴影替换为 --shadow-* 变量
**完成记录**: 200+ 处硬编码值替换为 CSS 变量，前端构建通过

### STYLE-2: 全局硬编码颜色消除
**问题**: MessageRow.vue (18处)、TracePanel.vue (9处)、ChatPanel.vue (3处) 仍有硬编码颜色
**方案**:
- [ ] MessageRow.vue: badge 颜色 (#9b59b6, #e74c3c, #f59e0b, #6366f1) 提取为 CSS 变量
- [ ] MessageRow.vue: 代码块颜色 (#1e1e1e, #d4d4d4) 提取为 --code-bg / --code-fg
- [ ] TracePanel.vue: 内联 style="color: #f56c6c" 替换为 CSS 类
- [ ] DashboardRenderer.vue: 加载动画颜色提取为变量
- [ ] 全局一致性检查确保无遗漏

### FLOW-6: 问数驱动建模端到端增强
**问题**: 对话中建模型的流程不够流畅，缺少从探索→特征工程→训练→验证→发布的完整引导
**方案**:
- [ ] 确保对话中 mining-model.md 的 workflow 引导完整（探索→特征选择→训练→验证→发布）
- [ ] 对话中训练的模型必须经过样本外验证，验证不通过时 LLM 应建议调整
- [ ] 对话中创建的模型自动同步到 MiningManager 模型列表
- [ ] 已有模型支持对话中调参重训 (retune action)
- [ ] 已有模型支持对话中微调 (fine-tune with new data)

### FLOW-7: 模型发布与调度配置
**问题**: 模型发布后缺少完整的调度管理界面
**方案**:
- [ ] MiningManager 模型卡片增加"已发布"标签和状态徽章
- [ ] 发布配置面板: 调度频率 (cron)、input 表、input 筛选条件 (${etl_date} 变量)
- [ ] output 表配置: 目标表名、写入模式 (追加/替换)、无表时自动建表
- [ ] 调度执行记录: 上次执行时间、下次执行时间、执行结果
- [ ] 已发布模型"下线"操作 (停用调度 + 状态回退)

### ARCH-14: 前端组件内状态收敛到 Pinia Store
**问题**: MiningManager.vue 仍有大量组件内状态 (models, selectedModel 等)
**方案**:
- [ ] 审计 MiningManager.vue 组件内状态，识别应移入 useMiningStore 的部分
- [ ] 审计 PipelineEditor.vue 状态，考虑是否需要 usePipelineStore
- [ ] 确保所有 store 状态可被其他组件共享访问

### LOG-7: 日志轮转与清理策略
**问题**: JSONL 日志无限增长，无清理策略
**方案**:
- [ ] ConversationEventLogger 添加日志轮转: 保留最近 N 天 (默认30天)
- [ ] 添加应用启动时的过期日志清理逻辑
- [ ] application.yml 可配置保留天数

### AGENT-5: 多智能体集成实际场景
**问题**: AgentTaskExecutor 和 Coordinator 已实现但仅用于 compare action
**方案**:
- [ ] 识别更多可并行场景: 数据探索多表并行、特征工程多方案并行
- [ ] ReActEngine 识别可并行的多 tool_calls 并行执行
- [ ] 并行训练场景结果自动注入主对话上下文

### PARA-8: 并发对话资源隔离
**问题**: 多用户并发对话时，所有请求共享 JVM 资源，无隔离
**方案**:
- [ ] 对话级上下文隔离: 确保每个对话的 LLM 调用互不干扰
- [ ] 训练任务队列增强: 显示排队位置，支持取消排队
- [ ] 资源使用指标暴露: 训练队列深度、活跃对话数、Python 进程数

**执行顺序**: STYLE-1 ✅ → STYLE-2 ✅ → COMPACT-1 ✅ → RECOVER-1 ✅ → CONCURRENCY-1 ✅ → LOG-8 ✅ → TASK-DEP-1 ✅
**原则**: 彻底完成一个，编译/构建验证通过，再做下一个

### COMPACT-1: 微压缩 — 选择性清除旧工具结果 ✅
**差距来源**: claude-code `microCompact.ts` — 不用 LLM 摘要，直接清除旧工具输出
**真实现状**: ContextCompactor 只有一种策略 — 调 LLM 生成摘要，成本高、慢
**方案**:
- [x] 新增 `microCompact()` — 清除超过 N 轮的 tool_result 内容，替换为"[旧结果已清除]"
- [x] 保留最近 5 轮工具结果完整内容
- [x] 清除逻辑: sql_result → 保留行数摘要, python_result → 保留最终结果
- [x] 触发条件: 上下文超过 60K token 时自动执行微压缩（比 LLM 摘要更快更省）
- [x] LLM 摘要降级为最后手段（微压缩仍不够时才触发）
**验证标准**: 20 轮对话后，上下文 token 数从 ~80K 降至 ~40K，LLM 仍能回答关于早期表名的问题

### RECOVER-1: JSONL 会话恢复 — 从日志重建对话 ✅
**差距来源**: claude-code `sessionTranscript` + `matchSessionMode` — 完整会话恢复
**真实现状**: ConversationController 恢复只读 DB 消息，JSONL 仅用于 trace 展示
**方案**:
- [x] 增强 `recoverConversation()` — 跨日期目录搜索，合并所有 JSONL 文件
- [x] assistant_chunk 事件自动合并为完整消息
- [x] 处理更多事件类型: thinking, sql_result, python_result, chart, report, dashboard
- [x] 保留 toolCallId 用于上下文重建
- [x] 恢复后写入 DB 补齐缺失消息 + 记录 session_recovered 事件

### CONCURRENCY-1: 对话级并发隔离 ✅
**差距来源**: claude-code 多 Agent 并行 + worktree 隔离
**真实现状**: 多用户同时对话时，共享 Spring 上下文，对话间无隔离
**方案**:
- [x] ConversationContextHolder — ThreadLocal 绑定 conversationId + dataSourceId
- [x] SessionManager — 活跃会话注册/注销，跟踪在线对话
- [x] ChatController 集成 — 异步线程设置/清理 ThreadLocal
- [x] QueryTracer 清理 — cleanupConversation() 防止内存泄漏
- [x] 监控端点: GET /api/v1/admin/sessions — 活跃对话数和详情

### LOG-8: 结构化遥测 — token 使用 + 执行耗时指标 ✅
**差距来源**: claude-code `analytics/` + `telemetry/` — 全链路指标收集
**真实现状**: 只有 JSONL 事件日志，无聚合指标
**方案**:
- [x] ConversationStatsService — 聚合工具调用耗时、成功率、每日摘要
- [x] 每次对话结束记录 token 使用摘要
- [x] ReActEngine 工具执行耗时记录
- [x] 管理端点: GET /api/v1/admin/stats — 聚合指标
- [x] 前端 AdminStatsPanel — 展示总览、模型使用、工具调用、每日摘要

### TASK-DEP-1: 任务依赖 DAG — blocks/blockedBy 模式 ✅
**差距来源**: claude-code `TodoWrite` 的 blocks/blockedBy 依赖管理
**真实现状**: Coordinator 有 DAG 但只用于并行执行，无显式依赖声明
**方案**:
- [x] AgentTask 扩展 blockedBy/blocks 字段 + isBlockedBy() 方法
- [x] Coordinator 调度前检查 blockedBy（优先 Plan-level deps，回退 AgentTask.blockedBy）
- [x] 死锁检测：检测到循环依赖时直接执行剩余任务
**验证标准**: 创建有依赖关系的 3 个子任务，按依赖顺序执行

---

## 第十轮：五维度深度审计 + 问数驱动建模全链路 (2026-05-15)

> 现状评估：第九轮完成了 COMPACT-1/RECOVER-1/CONCURRENCY-1/LOG-8/TASK-DEP-1。
> 本轮聚焦：(1) 五维度系统性深度审计 vs claude-code-sourcemap (2) 问数驱动建模全链路打通

### ARCH-15: 工具注册动态化 — 热加载 + 健康检查
**差距**: ToolRegistry 构造时硬编码注入，无热加载、无版本、无健康检查
**方案**:
- [ ] ToolRegistry 支持 `registerTool()`/`unregisterTool()` 运行时变更
- [ ] 每个工具暴露 `healthCheck()` 方法，定期验证可用性
- [ ] 工具元数据扩展: category, version, description
- [ ] `/api/v1/admin/tools` 端点列出已注册工具及状态
**验证**: 启动后通过 API 查看工具列表，手动注册/注销工具

### PROMPT-5: 提示词版本管理 + 条件渲染增强
**差距**: 提示词只有 `{{key}}` 简单替换，无版本、无条件逻辑、无 A/B 测试
**方案**:
- [ ] 提示词文件头部添加 `version:` 和 `description:` YAML frontmatter
- [ ] 支持 `{{#if condition}}...{{/if}}` 条件渲染块
- [ ] 提示词加载时记录版本号到 JSONL 审计日志
- [ ] QueryContextAssembler 组装时记录使用的提示词版本
**验证**: 在 mining-guidance.md 添加条件块，不同数据源触发不同内容

### LOG-9: JSONL 日志轮转 + 保留策略
**差距**: JSONL 只按日期分文件，无大小限制、无压缩、无清理策略
**方案**:
- [ ] 单文件超过 50MB 时滚动为 `{date}-{n}.jsonl`
- [ ] 超过 30 天的日志自动压缩为 `.jsonl.gz`
- [ ] 超过 90 天的压缩日志自动删除
- [ ] `/api/v1/admin/logs` 端点查看日志文件列表和大小
**验证**: 产生大量日志后验证滚动和压缩

### AGENT-6: 多智能体实时协作 — 子任务进度流式上报
**差距**: Coordinator 执行子任务时，主对话无法看到子任务实时进度
**方案**:
- [ ] AgentTaskExecutor 执行时通过事件总线向主对话推送进度事件
- [ ] Coordinator 支持 `Consumer<ReActEvent>` 回调，子任务事件透传给主 SSE 连接
- [ ] 前端 TracePanel 展示子任务嵌套 span
**验证**: 通过问数发起多表对比分析，前端实时看到子任务执行进度

### PARA-9: 多用户并发增强 — 用户级限流 + 排队可视化
**差距**: 只有对话级锁，无用户级并发限制，无排队位置提示
**方案**:
- [ ] RateLimiter 增加用户维度: `RateLimiter.tryAcquire("user:" + userId, maxConcurrency)`
- [ ] ChatController 并发拒绝时返回排队位置信息
- [ ] 前端显示"前方排队 N 人"提示
- [ ] 训练队列增强: 排队位置查询 API
**验证**: 同一用户发起 2 个并行请求，第二个显示排队信息

### QDM-1: 问数驱动建模 — 对话中引导式建模流程
**差距**: mining-model 工具有完整文档，但对话中 LLM 不会主动引导完整流程
**方案**:
- [ ] mining-guidance.md 增加"建模引导清单" — LLM 检测到建模意图时主动引导
- [ ] 引导流程: 探索数据 → 建议特征 → 创建模型 → 验证 → 训练 → 样本外检测 → 发布
- [ ] 每步自动检查前一步结果，缺少则提示补全
- [ ] 训练结果自动评估: 指标分析 + 过拟合检测 + 改进建议
**验证**: 新对话中说"帮我预测客户流失"，LLM 主动引导完成全流程

### QDM-2: 问数驱动建模 — 模型验证增强
**差距**: 验证模式存在但 LLM 不强制执行样本外/时间外检测
**方案**:
- [ ] 训练成功后自动触发验证检查: 如果 validation_mode=none，提示设置
- [ ] 新增 `validate_model_generalization` action — 用未参与训练的数据验证
- [ ] 时间外验证: 自动检测时间列，按时间先后分割
- [ ] 验证结果写入 JSONL (model_validation 事件)
- [ ] 发布前置检查: validation_mode != none 且指标达标才允许发布
**验证**: 训练模型后尝试直接发布，系统拦截并要求先做样本外验证

### QDM-3: 问数驱动建模 → 固化到挖掘模块
**差距**: 对话建模型后需要手动去挖掘模块查看，缺少无缝衔接
**方案**:
- [ ] 训练成功后自动创建 Pipeline (从模型配置反推节点)
- [ ] 对话中模型 → MiningManager 自动可见，标注"对话构建"来源
- [ ] 支持从对话中跳转到 PipelineEditor 查看节点流程
- [ ] 模型卡片显示来源标签: "对话构建" / "流程编排"
**验证**: 对话中训练一个模型后，切换到挖掘模块自动看到该模型和对应 Pipeline

### QDM-4: 已固化模型调参/微调 — 对话驱动
**差距**: 模型训练后只能通过挖掘模块手动调参，缺少对话中微调闭环
**方案**:
- [ ] 新增 `retune` action — 基于新数据或新超参数重新训练已有模型
- [ ] 对话中调参: "把模型X的树数量改到200并重新训练" → update_params + train
- [ ] 训练历史对比: 新旧指标并排展示，标注变化趋势
- [ ] 微调后自动更新 Pipeline 中的参数
**验证**: 对已有模型说"把学习率调低试试"，LLM 修改参数并重新训练

### FLOW-8: 发布模型调度执行 — 完整闭环
**差距**: 调度框架存在但缺少: 自定义 cron 表达式、执行失败告警、执行日志查看
**方案**:
- [ ] 支持标准 5 位 cron 表达式 (分 时 日 月 周)
- [ ] 执行失败时写入 JSONL (schedule_execution_failed) + 记录错误详情
- [ ] 前端展示调度执行历史: 成功/失败、耗时、行数
- [ ] 调度执行监控: 下次执行时间倒计时
**验证**: 发布模型设置每5分钟调度，观察执行历史

### FLOW-9: Output 节点建表增强
**差距**: 输出表存在时未检查列匹配，可能导致写入失败
**方案**:
- [ ] 输出前检查目标表列与预测结果列是否匹配
- [ ] 不匹配时自动 ALTER TABLE ADD COLUMN
- [ ] 新建表时根据预测结果列类型推断 DDL (VARCHAR/DOUBLE/INT)
- [ ] 支持 write_mode: append / replace / upsert (按主键)
**验证**: 模型预测产生新列，输出到已有表时自动添加列

**执行顺序**: ARCH-15 ✅ → PROMPT-5 ✅ → LOG-9 ✅ → AGENT-6 ✅ → PARA-9 ✅ → QDM-1 ✅ → QDM-2 ✅ → QDM-3 ✅ → QDM-4 ✅ → FLOW-8 ✅ → FLOW-9 ✅
**原则**: 彻底完成一个，编译/构建验证通过，再做下一个。每个完成后在对应项打 ✅
