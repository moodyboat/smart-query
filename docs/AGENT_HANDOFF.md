# Smart Query 智能体交接文档

> 最后更新：2026-08-27（Asia/Shanghai）  
> 工作目录：`C:\Users\Sakura\Desktop\国投\smart-query`  
> 本文面向接手该项目的开发智能体。先阅读本文，再查看代码和 `docs/mining-runtime.md`。

## 1. 项目目标和 Agent 定位

Smart Query 是一个自然语言问数、数据分析和机器学习建模平台。项目中的业务 Agent 不只是聊天机器人，其目标职责是：

1. 回答数据源、数据字典、业务指标、算法和模型结果相关问题；
2. 根据当前用户权限加载可访问的数据源、表和字段；
3. 与使用者共同设计建模方案，包括数据选择、预处理、特征工程、算法选择或自定义算法设计、验证方式和超参数调整；
4. 提交训练任务，持续反馈真实进度和日志，支持取消、失败分析和过程监控；
5. 分析训练结果、模型风险和发布条件，而不是只返回 Accuracy；
6. 将确认后的方案固化为可复用模板或 Pipeline；
7. 将 Pipeline 转换成可编辑流程图，并允许用户修改学习率、树深、阈值等参数后重新训练。

所有 Agent 工具必须服从后端资源权限检查。不能因为操作来自对话或工具调用就绕过 Controller/Service 层权限。

## 2. 仓库和 Git 状态

- GitHub：`https://github.com/moodyboat/smart-query.git`
- 当前分支：`feature/mining-security-dm8-refactor`
- 当前功能提交：`fa6d265 feat: secure and modernize mining workflow`
- 该提交包含 73 个文件，5524 行新增、2095 行删除。
- 创建本文之前工作区是干净的；本文创建后会产生新的文档修改。

### 远程推送阻塞

当前功能提交尚未推送到 GitHub。Windows 凭据中登录的是 GitHub 账号 `Basarakingaaa`，该账号对 `moodyboat/smart-query` 没有写权限，远程返回 HTTP 403。

处理方式：

1. 登录拥有 `moodyboat/smart-query` 写权限的 GitHub 账号，或给 `Basarakingaaa` 添加协作者权限；
2. 不要修改全局 `safe.directory`，使用单次参数推送：

```powershell
git -c safe.directory=C:/Users/Sakura/Desktop/国投/smart-query `
  push -u origin feature/mining-security-dm8-refactor
```

不要 force push，不要将此分支直接覆盖 `main`。

### GitLab 状态

完整旧项目的 GitLab 地址此前不可达，用户已明确要求暂停 GitLab，先以 GitHub 项目为准。不要因为 GitLab 暂时不可达而阻塞当前工作；需要恢复时还要移除环境变量中 HTTP/HTTPS 的 `1890` 强制代理。

## 3. 已完成的核心改造

### 3.1 用户、资源和异步身份隔离

已覆盖会话、建模模型、Pipeline、执行记录和对话工具调用：

- `UserContextHolder` 支持捕获、恢复和清理用户身份；
- 异步线程池在任务提交时捕获身份，在执行线程恢复，并在结束时清理；
- `ResourceAccessService` 集中检查模型、Pipeline、数据源等资源归属；
- Conversation、Chart、Dashboard、Report、MiningModel、MiningPipeline、Python 等 Controller 已接入权限检查；
- `MiningModelTool` 和工具执行上下文不再信任 Agent 提供的用户或资源 ID；
- 数据库迁移 `V18__resource_ownership_isolation.sql` 增加资源归属字段。

接手后不要重新引入仅靠 Controller 鉴权的设计。Service 和 Agent Tool 必须再次检查资源权限，因为任务可能来自定时器、异步线程或对话工具。

### 3.2 固定 Python 建模运行时

Java 不再使用大量 `StringBuilder` 动态生成 Python。训练、预测和 Pipeline 执行统一调用：

`backend/src/main/resources/python/mining_runtime.py`

当前协议：

```text
Java 校验权限和参数
  -> 写入带 protocolVersion 的 JSON 请求文件
  -> 启动固定 Python runtime
  -> runtime 写入独立 JSON 结果文件和 progress.json
  -> Java 读取结构化文件
  -> stdout/stderr 只保存日志
```

关键实现：

- Java 客户端：`MiningRuntimeClient.java`
- Python 进程管理：`PythonExecutor.java`
- 协议和运行说明：`docs/mining-runtime.md`

### 3.3 sklearn Pipeline 和防数据泄漏

训练与预测使用同一个完整 sklearn `Pipeline`：

- 数据切分发生在需要学习分布的预处理之前；
- 缺失值填充、日期处理、对数、分箱、特征交互、多项式、频率/目标编码、类别编码和缩放都进入 Pipeline；
- 交叉验证会 clone Pipeline，每一折只在该折训练数据上拟合预处理器；
- 预测直接加载训练制品中的 Pipeline，不维护第二套预处理代码；
- 旧版只保存 estimator 的 `.pkl` 不兼容新版预测，必须重新训练。

制品架构版本为 `3`，按模型版本和执行 ID 唯一保存，并包含 SHA-256、sklearn 版本、决策阈值、校准器和漂移基线。

### 3.4 异步训练、真实进度和 SSE

- 训练接口立即返回 `executionId`；
- 状态包括 `queued/running/success/failed/canceled`；
- Python 原子更新 `progress.json`；
- `sq_task_event` 持久化事件，支持事件 ID、心跳和 `Last-Event-ID` 续传；
- 前端使用带 JWT Header 的 fetch 流，避免原生 EventSource 无法携带 Authorization；
- 真实训练失败不再静默回退成模拟进度；
- SSE 只订阅任务事件，不为每条连接创建休眠轮询线程。

关键文件：

- `TaskEvent.java`
- `TaskEventMapper.java`
- `TaskEventService.java`
- `frontend/src/api/sse.js`
- `frontend/src/components/mining/TrainingDialog.vue`

### 3.5 模型评估和治理

分类评估已扩展为：

- Balanced Accuracy、Macro F1 和逐类指标；
- 风险类 Precision、Recall；
- PR-AUC、ROC-AUC、KS、Lift、Brier Score；
- Group Split/Group CV，降低企业、客户、合同等实体泄漏；
- 真正独立 OOS 表及样本快照 SHA-256；
- 多窗口 walk-forward 时间外验证；
- sigmoid/Platt 或 isotonic 概率校准；
- 固定阈值、最大 F1、最低召回和成本矩阵阈值优化；
- 发布治理门槛，`force` 不能绕过质量硬失败；
- 模型特征分布和预测分数基线、PSI 与缺失率变化监控。

实现入口主要在 `MiningService.java`、`MiningPredictionService.java`、`ModelDriftMonitorService.java` 和 `mining_runtime.py`。

### 3.6 自定义算法

项目确实需要自定义算法，当前约束为：

- 只有管理员可以新增、修改和删除自定义算法模板；
- Agent 可以协助用户设计模板，但不能自行扩大权限；
- 模板只负责导入依赖并构造 sklearn 兼容的 `clf`；
- 模板可以使用 `params`、`X`、`y`、`df`、`_model_type`；
- 模板不得自行执行 `fit`，也不得绕开统一 Pipeline 做预处理；
- 运行前有静态安全检查。

当前静态检查和管理员限制仍不能等同于安全沙箱。生产环境还需强制无网络、只读文件系统、非 root、CPU/内存/磁盘/时长限制，尤其注意后端挂载 Docker Socket 后的宿主机风险。

## 4. 数据库和 Docker 部署现状

### 4.1 当前数据库

本机部署已从 MySQL 切换为达梦 DM8：

- 镜像：`dm8_single:dm8_20241022_rev244896_x86_rh6_64`
- 容器：`dm8`
- 端口：`5236`
- `CASE_SENSITIVE=0`
- `COMPATIBLE_MODE=4`
- 系统 Schema：`SMART_QUERY`
- 示例业务 Schema：`SMART_QUERY_SAMPLE`

本地开发默认连接参数：

- 用户：`SYSDBA`
- 密码：`Dameng123`
- 系统 Schema 初始化 SQL：`SET SCHEMA SMART_QUERY`

这些只是本机开发默认值，正式环境必须通过环境变量替换。

### 4.2 数据迁移

原 MySQL 两个库均已迁移：

- `smart_query` -> `SMART_QUERY`
- `smart_query_sample` -> `SMART_QUERY_SAMPLE`

切换时完成逐表行数核对：系统库 26 张表、业务库 28 张表一致。后续接口和建模验证又生成了新的会话、任务事件和预测结果记录，因此当前行数可能高于迁移瞬间。

代表性业务数据：

- `customer`：100 行
- `account`：200 行
- `transaction`：2700 行
- `loan`：33 行
- `orders`：15 行

动态数据源记录 ID 为 `2`，其关键配置为：

- 类型：`dm`
- Host：`dm8`
- Port：`5236`
- Database/Schema：`SMART_QUERY_SAMPLE`
- 初始化 SQL：`SET SCHEMA SMART_QUERY_SAMPLE`

Java API 和 Python SQLAlchemy 均已实际读取该数据源。Python 查询 `customer` 返回 100 行。

### 4.3 MySQL 回退资产

- 项目 MySQL 容器 `smart-query-mysql-1` 已删除；
- `mysql:8.0.33` 镜像已删除；
- 原数据卷 `smart-query_mysql_data` 保留；
- 迁移前备份：`dist/mysql_before_dm8.sql`，该目录已被 Git 忽略；
- 备份 SHA-256：`FD357049BBF76CA1040FC1EC44E14342175007C1675EEE8628F2B2B084EC62E0`。

除非用户明确要求并再次核对绝对目标，不要删除该数据卷或 SQL 备份。

### 4.4 服务和端口

上次本机验收状态：

| 服务 | 容器/镜像 | 本机端口 | 状态 |
|---|---|---:|---|
| 前端 | `smart-query-frontend:latest` | 5174 | HTTP 200 |
| 后端 | `smart-query-backend:latest` | 9001 -> 9000 | 登录和核心 API 正常 |
| DM8 | `dm8` | 5236 | healthy |
| Redis | `redis:7-alpine` | 6379 | healthy |
| Python 建模 | `smart-query-python:latest` | 非常驻 | 由后端按任务启动 |

启动和检查：

```powershell
docker compose up -d
docker compose ps
docker compose logs --tail 200 backend
docker compose logs --tail 200 dm8
```

前端：`http://localhost:5174`  
后端：`http://localhost:9001`

当前 DM8 镜像使用开发许可证，容器日志显示有效期至 `2026-09-09`。正式部署前必须更换正式许可证或正式镜像。

## 5. LLM 配置和敏感信息

- 当前默认模型提供方为 DeepSeek；
- 用户提供的真实 DeepSeek Key 保存在本机 `.env`；
- `.env` 已被 Git 忽略；
- `.env.example` 只保留空占位符；
- 不要在日志、交接文档、提交信息、测试快照或回答中打印真实 Key；
- 不要把用户此前发在对话中的 Key 再写回源代码；
- 本地默认管理员为 `admin/admin123`，正式环境必须更换；
- JWT 默认密钥只适用于本地开发，正式环境必须注入至少 32 字节随机密钥。

## 6. 关键 API

### 数据源

- `GET /api/v1/datasource`
- `POST /api/v1/datasource/{id}/test`
- `POST /api/v1/datasource/{id}/test-detailed`
- `GET /api/v1/datasource/{id}/tables`
- `GET /api/v1/datasource/{id}/tables/{table}/columns`
- `GET /api/v1/datasource/{id}/tables/{table}/preview?limit=20`

### 异步模型训练

- `POST /api/v1/mining/model/{modelId}/train`
- `GET /api/v1/mining/model/{modelId}/executions/{executionId}`
- `POST /api/v1/mining/model/{modelId}/executions/{executionId}/cancel`
- `GET /api/v1/mining/model/{modelId}/train-stream?executionId=...`
- `GET /api/v1/mining/model/{modelId}/artifact-status`
- `POST /api/v1/mining/model/{modelId}/artifact-migration`

SSE 必须携带 JWT。前端应统一使用 `frontend/src/api/sse.js`，不要重新使用无法设置 Authorization Header 的原生 EventSource。

## 7. 验证记录

已完成：

- 后端曾完成 `mvn -DskipTests package` 编译；
- 本轮提交前 `git diff --check` 通过；
- `mining_runtime.py`、对应 Python 测试和 MySQL->DM 转换脚本通过 Python 语法检查；
- Docker Compose 配置解析通过；
- 管理员登录、`/auth/me`、数据源列表、详细连接测试、表列表、字段列表和数据预览通过；
- 达梦 `customer` 预览为 100 行、18 列；
- Python 建模容器通过 `dmPython + dmSQLAlchemy` 查询达梦 `customer`，返回 100；
- 前端首页 HTTP 200；
- 权限隔离后，普通会话列表只返回当前用户可见会话，访问无归属的旧会话 ID 返回 404。

未完成或刻意暂停：

- 用户明确要求暂时不做复杂测试，因此没有运行完整 Java/Python/前端测试套件；
- 最近一次重复 Maven 编译因首次全量下载依赖过慢被主动中止，不是编译错误；
- 尚未完成一次完整 UI 闭环：Agent 设计方案 -> 保存 Pipeline -> 异步训练 -> SSE -> 结果分析 -> 发布 -> 修改参数重训；
- 尚未对自定义算法执行容器做生产级安全沙箱验收；
- 尚未对旧 `.pkl` 批量执行重训迁移。

## 8. 已知问题和注意事项

1. **远程分支未推送**：必须先解决 GitHub 账号权限。
2. **README 已过时**：根目录 `README.md` 仍写 MySQL、GLM 默认模型和 V16，不能作为当前事实；应更新为 DM8、DeepSeek 和 V21+。
3. **开发许可证到期**：DM8 开发许可证到期日为 2026-09-09。
4. **旧模型不兼容**：旧 estimator-only `.pkl` 需要重新训练，不能伪装成新版 Pipeline 制品。
5. **Python 镜像重建未做干净验收**：由于网络下载 XGBoost 等大依赖不稳定，本机 `smart-query-python:latest` 是在原可用镜像上增量安装达梦驱动得到；Dockerfile 已补充可复现配置，但网络稳定后应做一次 clean build。
6. **自定义算法仍是高风险面**：静态检查不能代替容器隔离，特别是 Docker Socket 挂载会放大风险。
7. **数据库详细检测显示能力有限**：达梦连接测试成功，但版本/部分元数据权限可能显示 `Unknown/false`，需区分展示能力与实际 SELECT/EXPLAIN 能力。
8. **上线漂移需要标签回流**：当前可监控输入和分数漂移；真正的 Recall、Precision 等线上性能下降仍依赖业务标签回流。
9. **不要恢复训练失败后的模拟进度**：模拟进度只能用于明确的演示模式。
10. **不要从 stdout 解析结果**：stdout/stderr 永远只作为日志通道。

## 9. 关键文件导航

### 权限和身份

- `backend/src/main/java/com/smartquery/common/UserContextHolder.java`
- `backend/src/main/java/com/smartquery/config/AuthInterceptor.java`
- `backend/src/main/java/com/smartquery/config/ThreadPoolFactory.java`
- `backend/src/main/java/com/smartquery/service/ResourceAccessService.java`
- `backend/src/main/java/com/smartquery/tool/ToolExecutionContext.java`
- `backend/src/main/java/com/smartquery/tool/impl/MiningModelTool.java`

### 建模运行时

- `backend/src/main/resources/python/mining_runtime.py`
- `backend/src/main/java/com/smartquery/service/MiningRuntimeClient.java`
- `backend/src/main/java/com/smartquery/python/PythonExecutor.java`
- `backend/src/main/java/com/smartquery/service/MiningService.java`
- `backend/src/main/java/com/smartquery/service/PipelineService.java`
- `backend/src/main/java/com/smartquery/service/MiningPredictionService.java`
- `docs/mining-runtime.md`

### 任务、SSE 和治理

- `backend/src/main/java/com/smartquery/service/TaskEventService.java`
- `backend/src/main/java/com/smartquery/service/ModelDriftMonitorService.java`
- `frontend/src/api/sse.js`
- `frontend/src/components/mining/TrainingDialog.vue`
- `frontend/src/composables/usePipelineStream.js`
- `backend/src/main/resources/db/migration/V20__async_training_and_artifact_version.sql`
- `backend/src/main/resources/db/migration/V21__evaluation_governance_and_task_events.sql`

### 达梦和部署

- `docker-compose.yml`
- `docker-compose.local.yml`
- `docker/dm8-entrypoint.sh`
- `docker/python/Dockerfile`
- `docker/python/requirements-dm8.txt`
- `scripts/mysql-dump-to-dm8.py`
- `scripts/Dm8SqlImporter.java`
- `scripts/Dm8TableCounts.java`

## 10. 建议的后续执行顺序

1. 解决 GitHub 写权限，提交本文并推送当前功能分支；
2. 更新根目录 `README.md`，清理 MySQL/GLM/V16 旧描述；
3. 在用户允许后运行目标测试，而不是一开始运行全部重型测试；
4. 用数据源 ID 2 做一次完整 UI/Agent 建模闭环；
5. 验证 Pipeline 流程图保存、重新打开和参数修改不会丢字段；
6. 验证目标编码、日期拆分、分箱和复杂缺失策略的训练/预测一致性；
7. 验证 Group、独立 OOS 和多窗口时间验证的失败条件及发布阻断；
8. 验证 SSE 心跳、断线重连、`Last-Event-ID` 续传和任务取消；
9. 将自定义算法执行收紧到生产级受限容器；
10. 更换正式 DM8 许可证，改用生产环境变量和密钥管理；
11. 对旧模型执行显式重训迁移；
12. 在有标签回流后补充线上性能监控和分群公平性检查。

## 11. 接手智能体的首轮检查清单

```powershell
cd C:\Users\Sakura\Desktop\国投\smart-query
git branch --show-current
git log -3 --oneline
git status --short
git remote -v
docker compose ps
docker compose logs --tail 100 backend
docker compose logs --tail 100 dm8
```

接手时先确认：

- 当前仍在 `feature/mining-security-dm8-refactor`；
- 用户的新修改没有被覆盖；
- `.env`、`dist/`、`.m2/`、`target/` 和编译缓存没有进入 Git；
- DM8、Redis、后端和前端仍在运行；
- 数据源 ID 2 的 Schema 仍是 `SMART_QUERY_SAMPLE`；
- 真实 Key 只存在于本机 `.env`；
- 未获得授权前，不删除 MySQL 回退卷和备份；
- 未获得用户明确要求前，不 force push、不合并 `main`、不执行复杂测试。

## 12. 可直接交给下一智能体的任务描述

> 先完整阅读 `docs/AGENT_HANDOFF.md` 和 `docs/mining-runtime.md`，保持在 `feature/mining-security-dm8-refactor` 分支。不要提交 `.env`、备份、缓存或真实密钥，不要删除 `smart-query_mysql_data`。先解决 GitHub 推送权限并推送现有提交，然后更新过时 README。之后用达梦数据源 ID 2 做一次 Agent 协作建模闭环验证：根据当前用户权限选择数据，协助设计预处理、算法和超参数，保存为可编辑 Pipeline 流程图，异步训练并通过带 JWT 的 SSE 展示真实进度，分析严格评估指标和治理门槛，再修改参数重训。发现问题时优先修正最小范围，并保留现有用户改动。
