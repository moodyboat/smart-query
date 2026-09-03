# Smart Query 项目工作交接文档

> 交接日期：2026-09-03（Asia/Shanghai）
> 项目目录：`C:\Users\Sakura\Desktop\国投\smart-query`
> 远程仓库：`https://github.com/moodyboat/smart-query.git`
> 当前分支：`feature/mining-security-dm8-refactor`
> 当前功能基线：`4bd1d98 feat: add governed orchestration v2 platform`

## 1. 交接结论

本阶段已经完成 Smart Query 从旧的“模型管理 + 线性挖掘 Pipeline”向“统一算子库 + 不可变版本 +
V2 DAG + 运行治理”的主体架构改造，并已在本机 Docker Desktop 环境构建、部署和验收。

当前系统的主要产品入口为：

- 调度中心：管理已发布并固化制品的模型成品；
- 模型流水线：管理仍可编辑的训练草稿；
- 算子库：统一管理数据加工、规则、机器学习、智能体、输出五类算子；
- 治理中心：管理版本审批、依赖申请、固定运行时、存储和运行状态；
- 流程编排：不再是一级入口，而是调度中心和模型流水线下的二级页面。

“模型版本”重复页面已经从调度中心和模型流水线移除。模型训练、评估和发布仍通过机器学习算子构建
流程完成，发布后的不可变算子版本进入统一审批与 V2 DAG 目录。

详细设计和 API 清单见 [算子编排 V2 架构](orchestration-v2.md)，建模协议见
[建模运行时架构](mining-runtime.md)。

## 2. Git 与交付状态

### 2.1 已推送代码

- 功能提交：`4bd1d98`
- 远程分支：`origin/feature/mining-security-dm8-refactor`
- 该提交包含 207 个文件，新增 V2 编排、算子版本、审批、依赖中心、运行时、输出结果、节点回放、
  存储归档及对应前端页面和测试。
- 功能提交已推送，本地和远程提交一致。

本文及文档索引属于交接后新增修改，接手时先运行 `git status --short` 确认是否已经另行提交。

### 2.2 分支要求

- 后续开发继续基于 `feature/mining-security-dm8-refactor`；
- 未经确认不要直接合并或覆盖 `main`；
- 禁止 force push；
- 推送时如果 Windows Git 报 `dubious ownership`，使用单次 `-c safe.directory=...`，不要修改全局配置。

## 3. 当前产品与页面结构

### 3.1 编排与治理

“编排与治理”内部有四个一级功能：

1. **调度中心**
   - 只展示 `published` 且具有模型路径和制品摘要的模型；
   - 支持模型调度配置；
   - 点击模型成品时，只有已生成并通过审批的 ML 算子版本才能进入 V2 DAG；
   - 当前本地数据库没有活动模型，因此页面为空态。
2. **模型流水线**
   - 展示 `draft/ready/running/failed` 状态的训练草稿；
   - 点击草稿进入统一 V2 DAG，并携带草稿来源上下文；
   - “编辑训练草稿”仍进入旧挖掘编辑器，用于配置和训练模型；
   - 已训练流水线不会出现在草稿列表中。
3. **算子库**
   - 使用五个清晰的类型子页签：数据加工、规则、机器学习、智能体、输出；
   - 新建机器学习算子会打开数据挖掘构建页面；
   - 其他类型进入各自的对话式草稿和发布工作台。
4. **治理中心**
   - 版本人工审批；
   - 依赖中心和运行时构建任务；
   - 存储保留、归档恢复和运行监控。

关键前端文件：

- `frontend/src/components/OrchestrationGovernanceHub.vue`
- `frontend/src/components/OperatorLibraryCenter.vue`
- `frontend/src/components/V2DagDesigner.vue`
- `frontend/src/components/V2DagCanvas.vue`
- `frontend/src/components/MiningManager.vue`
- `frontend/src/components/mining/ModelScheduleCenter.vue`
- `frontend/src/components/pipeline/PipelineList.vue`

### 3.2 输出与线索

输出是普通算子，当前支持：

- `LEAD`：标准线索；
- `CHART`：图表；
- `TABLE`：数据表；
- `EXCEL`：类 Excel 网格可视化，不提供文件下载。

输出算子不是强制节点，`LEAD` 也不是强制输出。是否生成线索由流程显式连接的输出算子决定。
无论上游经过规则、机器学习还是智能体，平台都通过以下保留字段维持结果与原始输入的关系：

- `__sourceRefs`
- `__sourceSnapshots`
- `__evidence`

边使用 `PROJECT` 映射时也不能删除这些平台血缘字段。结果查看器可逐行展开原始输入、预测结果和判断依据。

输出算子的发布链路为：

```text
对话生成草稿 -> 沙箱整形 -> 预览验证 -> 创建候选版本 -> 人工审批 -> 发布
```

### 3.3 规则、SQL、智能体和机器学习

- 规则算子允许通过对话生成完整 Python 规则，不局限于拖拽固定积木；
- Python 规则必须实现 `evaluate(records, parameters)`，并在隔离 Docker 沙箱完成正常/边界测试；
- `RULE_DSL` 仍作为低代码安全解释器保留；
- `SQL_AST` 只允许单条只读 `SELECT`，固定数据源、表白名单、参数和返回上限；
- `AGENT_POLICY` 固定模型、只读工具、数据范围和预算，不能在运行时扩大权限；
- ML 节点使用已发布模型的固定制品、摘要和运行时，预测结果会附加到原输入记录而不是替换原记录。

## 4. 后端架构边界

### 4.1 五类算子与不可变版本

算子类型为 `DATA/RULE/ML/AGENT/OUTPUT`。`sq_operator_definition` 保存稳定身份，
`sq_operator_version` 保存不可变快照。流程同样分为 `sq_flow_definition` 和 `sq_flow_version`。

流程节点必须绑定确切 `operatorVersionId`，不能在执行时自动追随“最新版”。已发布版本绑定固定
`runtimeProfile` 和镜像摘要，运行时和依赖升级必须生成新档案和新算子版本。

### 4.2 固定运行时与依赖中心

运行时家族相互隔离：

| 依赖类型 | 运行时 |
| --- | --- |
| Python 包 | `RULE_PYTHON` |
| ML 算法 | `ML_MODEL` |
| JDBC 驱动 | `DATA_CONNECTOR` |
| 智能体工具 | `AGENT_GATEWAY` |
| 前端渲染器 | `OUTPUT_RENDERER` |

运行过程中禁止临时安装依赖。依赖缺失会把草稿标记为 `DEPENDENCY_MISSING` 并阻止执行/发布。
依赖申请审批通过后只创建构建任务，不直接安装软件；外部 CI/构建器通过 HMAC Worker API 构建不可变镜像。

注意：仓库实现了依赖申请、审批、构建任务、租约、回调校验和草稿重验，但企业侧真正的 CI/镜像构建
Worker 仍需接入。未配置 `RUNTIME_BUILDER_HMAC_SECRET` 时 Worker API 会以 503 关闭。

### 4.3 DAG 执行与审计

执行引擎已实现：

- DAG 环路、连接性、版本状态及 Schema 兼容预检；
- 拓扑分层，同层并行、跨层等待；
- 边 `MERGE/PROJECT` 字段映射；
- 运行租约、fencing token、心跳续租和过期恢复；
- 取消、节点超时和最终事务提交；
- 节点输入/输出快照和固定版本回放；
- 输出字段索引、白名单筛选、稳定排序和签名游标分页；
- 热数据容量预留、过期归档、摘要校验和恢复。

正式业务写入集中在整条 DAG 成功后的最终事务。失败、取消、超时或失去租约的实例不能提交半批线索
或半批可视化结果。

关键后端入口：

- `backend/src/main/java/com/smartquery/controller/OrchestrationV2Controller.java`
- `backend/src/main/java/com/smartquery/orchestration/OrchestrationRunService.java`
- `backend/src/main/java/com/smartquery/orchestration/VersionCatalogService.java`
- `backend/src/main/java/com/smartquery/orchestration/OperatorApprovalService.java`
- `backend/src/main/java/com/smartquery/orchestration/DependencyCenterService.java`
- `backend/src/main/java/com/smartquery/orchestration/RuntimeBuildJobService.java`
- `backend/src/main/java/com/smartquery/orchestration/OutputAuthoringService.java`
- `backend/src/main/java/com/smartquery/orchestration/PolicyAuthoringService.java`
- `backend/src/main/java/com/smartquery/orchestration/RuleAuthoringService.java`
- `backend/src/main/java/com/smartquery/orchestration/StorageGovernanceService.java`

## 5. 数据库和本地数据状态

### 5.1 数据库

- 系统数据库：达梦 DM8；
- 系统 Schema：`SMART_QUERY`；
- 示例业务 Schema：`SMART_QUERY_SAMPLE`；
- 动态数据源 ID：`2`；
- 业务库类型可以是 MySQL、PostgreSQL、DM 或 GBase；
- 项目没有启用 Flyway，现有 V1 表由历史 seed/DataSeeder 管理，V2 表由
  `OrchestrationV2SchemaSeeder` 可重复创建和补列。

V2 关键表按领域分为：

- 算子与流程：`sq_operator_definition`、`sq_operator_version`、`sq_flow_definition`、`sq_flow_version`；
- 审批与运行时：`sq_operator_version_approval`、`sq_runtime_profile`、`sq_runtime_dependency`、
  `sq_dependency_request`、`sq_runtime_build_job`；
- 运行与回放：`sq_orchestration_run`、`sq_node_run`、`sq_node_run_snapshot`、`sq_node_replay`；
- 线索与输出：`sq_lead`、`sq_lead_source_snapshot`、`sq_lead_evidence`、`sq_output_artifact`、
  `sq_output_artifact_row`、`sq_output_artifact_cell`；
- 存储治理：`sq_storage_policy`、`sq_storage_usage`、`sq_archive_record`、`sq_archive_chunk`。

### 5.2 本机数据快照

交接时本机数据库状态：

- 活动旧模型：`0`；
- 活动模型流水线：`3`（ID 1、2、3）；
- 流水线状态：ID 1=`trained`、ID 2=`ready`、ID 3=`failed`；
- 原模型 ID 6、7、8、9 已逻辑删除；
- 3 个旧 `.joblib` 模型制品已从 `sq_workspace` 物理删除；
- 模型删除接口已修复，不再出现“返回成功但逻辑删除未落库”。

上述数据库和 Docker Volume 数据不在 Git 中。新机器拉取代码后不会自动获得本机的用户数据、流水线、
模型、运行记录或制品。逻辑删除的模型记录可从数据库备份恢复；已物理删除的制品只能从 Volume/外部备份恢复。

## 6. Docker Desktop 部署

### 6.1 当前服务

| 服务 | 默认访问 | 说明 |
| --- | --- | --- |
| frontend | `http://localhost` | Nginx，宿主机 80 端口 |
| backend | `http://127.0.0.1:9001` | 容器内 9000 |
| dm8 | `127.0.0.1:5236` | 系统库和示例业务库 |
| redis | Docker 内网 6379 | 任务/运行辅助服务 |
| python | 非常驻 | 后端按任务启动固定 Python 容器 |

共享资源：

- Docker 网络：`smartquery-net`；
- Python 临时产物卷：`sq_artifacts`；
- 模型工作区卷：`sq_workspace`；
- DM8 数据使用 Compose 命名卷持久化。

### 6.2 常用命令

```powershell
cd C:\Users\Sakura\Desktop\国投\smart-query

# 构建前后端；后端镜像构建阶段会执行 Maven package 和测试
docker compose build backend frontend

# Python 固定运行时单独构建
docker compose --profile tools build python

# 启动和检查
docker compose up -d
docker compose ps
docker compose logs --tail 200 backend
docker compose logs --tail 200 dm8
```

当前 Windows 宿主机没有全局 Maven，后端测试通过 Docker 构建执行。不要因为本机 `mvn` 命令不存在
误判后端无法构建。

## 7. 验证结果

本阶段最后一次验收结果：

- 后端 Maven 测试：153 项通过，0 失败，0 错误；
- 前端 `npm run build`：通过；
- Docker 前后端镜像：构建成功；
- frontend、backend、dm8、redis：全部 healthy；
- 管理员 API：活动模型 0、活动流水线 3；
- 页面：调度中心和模型流水线均无“模型版本”页签；
- 调度中心：正确显示 0 个已发布模型；
- 模型流水线：正确显示 2 个可编辑草稿，已训练流水线按设计过滤；
- 浏览器控制台：无错误。

前端构建仍有非阻塞警告：部分 bundle 大于 500 KiB，依赖中的 `#__PURE__` 注释会触发 Rolldown
位置警告。后续可以做路由级懒加载和代码分包，但不影响当前运行。

## 8. 权限与安全约束

### 8.1 必须保持的边界

- 普通用户只能访问本人模型、流水线、算子、流程、运行、线索和输出；管理员可查看全部；
- Controller、Service、异步任务和 Agent Tool 都必须检查资源归属；
- 作者不能审批自己创建的算子版本；
- 算子版本和流程版本一经创建不得修改或删除，只能废弃；
- 运行时与依赖被引用时只能废弃，不能物理删除；
- 规则 Python 必须在无网络、只读根文件系统、能力移除和资源受限的沙箱中运行；
- SQL 和智能体节点不能通过画布配置覆盖版本锁定的白名单和预算；
- 线索与输出必须保留原始输入快照和证据；
- 不要恢复运行时临时安装依赖、stdout 解析业务结果或失败后伪造成功结果等旧行为。

### 8.2 密钥与上线前阻断项

- `.env` 含本机敏感配置且已被 Git 忽略，禁止提交或打印；
- `backend/src/main/resources/application.yml` 仍存在硬编码 GLM Key 默认值，这是高优先级安全债务；
  应立即轮换该 Key、改为空环境变量占位，并评估清理 Git 历史；
- 生产环境必须替换默认管理员密码、DM8 密码和 JWT 密钥；
- `OUTPUT_QUERY_CURSOR_SECRET` 应使用独立随机密钥，不建议生产环境继续回退到 JWT 密钥；
- 外部 Runtime Builder 必须配置至少 32 字节的 `RUNTIME_BUILDER_HMAC_SECRET`，并配合 nonce、时间窗、
  lease token、SBOM、provenance、许可证和漏洞扫描门禁；
- 不要在生产业务进程中授予镜像仓库写权限；镜像构建应由独立 CI/Worker 完成。

## 9. 已知问题与未完成工作

按优先级建议处理：

1. **P0：轮换并移除仓库中的硬编码 GLM Key**，检查远程仓库和历史提交暴露范围；
2. **P0：补齐生产密钥和权限配置**，包括 JWT、输出游标、Runtime Builder HMAC、数据库凭据；
3. **P1：接入企业 CI/Runtime Builder**，完成依赖审批后的自动镜像构建、扫描、签名和回调；
4. **P1：使用正式数据库迁移工具**，逐步替代大型启动时 Schema Seeder；
5. **P1：在目标 DM8/MySQL 数据规模上压测** DAG 执行、结果索引、游标分页、归档和恢复；
6. **P1：补一次真实业务全链路验收**：输入 -> 规则/ML/智能体 -> 多输出 -> 原始输入展开 -> 回放；
7. **P1：验证多实例故障恢复**，覆盖租约过期、进程中止、重复回调和最终提交 fencing；
8. **P2：前端路由懒加载和代码分包**，降低主 bundle 体积；
9. **P2：更新根 `README.md` 和 `CLAUDE.md`**，其中仍有旧页面结构、旧部署和旧风险描述；
10. **P2：将 V2 Schema 和 API 纳入持续集成**，保留 DM8/MySQL 双数据库兼容测试。

## 10. 接手后的首轮检查

```powershell
cd C:\Users\Sakura\Desktop\国投\smart-query

git branch --show-current
git log -3 --oneline --decorate
git status --short
git remote -v

docker compose ps
docker compose logs --tail 100 backend
docker compose logs --tail 100 dm8
```

接手人应确认：

- 当前仍在 `feature/mining-security-dm8-refactor`；
- 功能基线至少包含 `4bd1d98`；
- `.env`、`dist/`、`target/`、模型制品和数据库备份没有进入 Git；
- Docker 四个常驻服务均健康；
- 新增修改没有覆盖用户未提交的工作；
- V2 算子目录只返回 `PUBLISHED` 版本；
- 未配置 Runtime Builder HMAC 时外部构建接口保持关闭；
- 未经明确授权不删除 Docker Volume、归档、数据库记录或远程分支。

## 11. 推荐的下一阶段验收场景

建议选取“重复支付订单”或“逾期预测”作为端到端案例：

1. 用 `DATA/SQL_AST` 读取订单或客户输入并生成来源快照；
2. 通过对话创建规则算子，完成沙箱测试、审批和发布；
3. 可选连接 ML 或智能体节点，保留预测概率、判断依据和输入参数；
4. 并行连接 `LEAD`、`CHART` 和 `EXCEL` 输出算子；
5. 验证 Excel 网格只做页面展示、不出现下载入口；
6. 展开每一条结果，核对原始订单/客户输入与证据；
7. 对一个节点发起回放，确认不会生成新的正式线索或输出；
8. 取消一次运行并模拟一次超时，确认没有半批结果落库；
9. 对大结果执行筛选、排序、签名游标分页、归档和恢复。

该场景可以同时验证本阶段最重要的三个目标：规则自由构建、任意流程的输入血缘保留，以及输出算子化的
多视图可视化。
