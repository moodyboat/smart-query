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

本阶段最后一次验收结果（输出结果出口重构前的基线）：

- 后端 Maven 测试：173 项通过，0 失败，0 错误；
- 前端 `npm run build`：通过；
- Docker 前后端镜像：构建成功；
- frontend、backend、dm8、redis：全部 healthy；
- 管理员 API：活动模型 0、活动流水线 3；
- 页面：调度中心和模型流水线均无“模型版本”页签；
- 调度中心：正确显示 0 个已发布模型；
- 模型流水线：正确显示 2 个可编辑草稿，已训练流水线按设计过滤；
- 浏览器控制台：无错误。

### 7.1 2026-09-03 算子全链路补充验收

- 智能体算子不再接受前端自定义运行模型，与 AI 工作台共用同一个
  `LlmService` 和平台默认模型配置；实际生成、预览和流程运行均固定为 `deepseek`；
- 规则沙箱已去除不兼容的 Docker `volume-subpath`，改为共享卷挂载加随机工作目录，
  仍保持无网络、只读根文件系统、cap-drop 和资源限制；旧规则草稿两组测试均已通过；
- 规则测试运行器会重建平台血缘字段，并兼容模型返回的 `expected` 数组或
  `{records:[...]}` 形式，避免草稿伪造或误写保留字段；
- `LEAD/CHART/TABLE/EXCEL` 统一使用平台内置渲染器，不接受 LLM 虚构的外部依赖；
  输出整形兼容单个 `sort` 对象以及 `order` 方向别名；
- 智能体输出 Schema 由服务端按真实运行契约生成：原记录字段 + 决策字段 + 轨迹字段，
  不再信任 LLM 返回的错误顶层结构；
- 数据库已增加 `qa_operator_reviewer` 测试账号，角色为数据库驱动的
  `operator_reviewer`，用于验证创建人与审批人分离；
- 已发布数据、机器学习、规则、智能体和输出算子版本，所有版本均经独立账号审批；
- 流程 `QA_客户风险全链路` v2（流程版本 `#2`）已完整运行成功：
  `DATA -> ML -> RULE -> AGENT -> EXCEL + LEAD`，6 个节点全部成功，实际处理 5 条记录，
  生成 5 条线索和 2 个就绪输出制品；
- 浏览器已确认统一算子库显示各已发布版本，流程运行记录显示 6/6 节点成功，
  前端控制台无 warning/error。

### 7.2 2026-09-03 可组合结果出口重构与验收

- 最新后端 Maven 测试为 177 项通过，0 失败，0 错误；新增导出测试直接校验五类文件签名并拒绝
  非导出制品调用下载接口；前端生产构建通过；
- 输出算子已经升级为 V2 可组合结果出口：`transformations[]` 负责可选转换，`targets[]`
  可同时声明持久化、展示、导出和业务动作，并由服务端强制要求至少一个目标；
- 新增数据库驱动的输出能力注册中心 `sq_output_capability` 和不可变版本表
  `sq_output_capability_version`。运行时会校验能力状态、发布版本、内容哈希、权限和安全策略快照，
  前端不再写死可选输出类型；
- 已启用 12 个内置能力：字段投影/排序、平台制品、临时结果、表格、ECharts、组合页面、
  XLSX/CSV/PDF/JSON/PNG 和风险线索；数据库、对象存储、Webhook、自定义代码组件四个能力已注册但保持
  `DISABLED`，必须完成真实适配器或隔离构建链路后才能启用；
- 组合页面仅允许 `metric/chart/table/filter/container` 可信组件；ECharts 配置经过字段、图表类型、
  聚合方式和可执行键过滤；图表渲染失败时前端保留普通表格降级入口；
- 自定义组件候选版本必须提交源码、测试报告、构建日志和 SHA256，且创建人不能审批自己的版本；
  当前未配置独立 TypeScript 沙箱构建器，因此该能力按安全默认值禁用；
- `REPLACE` 持久化模式需要 `governance.runtime.manage` 权限；版本快照和运行 ID 用于阻止能力漂移并为
  后续幂等适配器保留契约；
- 固定导出格式由后端真实生成，XLSX 包含结果页和数据血缘页，CSV/JSON 保留血缘字段，PDF/PNG
  使用后端无头中文字体渲染；后端镜像已加入 FreeType、Fontconfig 和文泉驿正黑字体运行依赖；
- 真实对话草稿 `#4` 已成功整形、用 5 条记录预览并由独立审批账号批准为算子版本 `#9`；
- 流程 `qa_composable_output_20260903` v1（流程 `#2`、版本 `#3`）实际运行 `#2` 成功，单个输出节点
  一次提交 10 个独立制品：运行制品、表格、图表、组合看板、5 种文件和线索出口；最终生成 3 条风险线索；
- 五种下载均通过 HTTP、MIME 和文件签名校验：XLSX=`PK`、CSV=`UTF-8 BOM`、PDF=`%PDF`、
  JSON=`[`、PNG=`89 50 4E 47`，没有使用伪文件或仅前端改扩展名。

### 7.3 2026-09-04 存储治理热数据权限与模型血缘验收

- “存储与运行 > 热数据”不再直接暴露原始 `OutputArtifact`，后端新增面向治理页面的批量聚合视图，
  显示独立输出目标、能力编码、输出类型、记录数、流程版本、运行状态、所有者和角色；
- 流程、算子和模型关联均从该次运行的 `NodeRun` 与不可变算子版本快照回溯，不使用流程草稿推测；
  详情抽屉按实际执行顺序显示算子版本，机器学习节点同时显示模型、算法、算法版本、状态和制品摘要；
- 权限分为 `OWNER`、`RESOURCE_ACCESS_ALL` 和 `METADATA_ONLY`：运行治理人员可以管理容量和归档，
  但只有产物所有者或具备全局资源读取权限的角色可以读取业务明细；明细接口仍执行服务端鉴权，
  不是只依靠前端隐藏按钮；
- 详情抽屉复用统一输出查看器，支持结果分页、原始输入、证据和血缘引用；无明细权限时只返回和展示治理元数据；
- 真实数据库现有 12 个热产物可区分为 11 类输出、10 个输出目标，其中 2 个成功关联机器学习模型；
  抽样 `QA_客户风险全链路` 产物回溯出 6 个实际节点和随机森林模型，并成功读取 5 行结果明细；
- 最新后端 Maven 测试为 179 项通过，0 失败，0 错误；前端 `npm run build` 通过；frontend、backend、
  dm8、redis 部署后全部 healthy。因 Docker Hub 认证端点当时网络超时，本次使用本机既有运行镜像替换
  已测试 JAR 和前端静态制品完成离线部署；恢复网络后可再执行标准 `docker compose build` 固化完整构建链路。

前端构建仍有非阻塞警告：部分 bundle 大于 500 KiB，依赖中的 `#__PURE__` 注释会触发 Rolldown
位置警告。后续可以做路由级懒加载和代码分包，但不影响当前运行。

### 7.4 2026-09-04 穿透式监控、调度迁移与双层审批口径

- 全局 Header 新增第 5 个一级入口“穿透式监控模型”，位于“模型中心”和“平台配置”之间；仅具备
  `platform.monitor.view`、`governance.runtime.manage` 或管理员权限的角色可见。它不属于 MLOps 治理内部页签；
- 原模型中心中的调度配置已迁入“穿透式监控模型 -> 调度管理”。这里统一管理生产模型的启停、定时重训、
  定时预测、Cron、输入表、输出表和输入过滤条件；模型中心只保留模型资产和流程编排，不再提供调度修改入口；
- 原“存储与运行”页面已移除“热数据”页签，只保留策略容量、节点回放、归档恢复和运行基础设施监控；
- 新监控接口为 `GET /api/v2/formal-task-monitor/dashboard`。模型任务只接收
  `sq_model_execution.trigger_type = schedule`；流程任务同时要求
  `sq_orchestration_run.run_mode = FORMAL` 和 `trigger_type = SCHEDULE`；
- `TRIAL/API` 流程运行、输出草稿整形、预览、节点回放和手工预测不会进入穿透式监控；当前历史 12 个
  输出均为试运行产物，因此新页面真实结果为 0，而不是把旧测试结果冒充正式调度数据；
- 模型执行增加 `execution_kind` 和 `output_summary`；预测结果增加 `trigger_type` 与
  `model_execution_id`。定时预测现在会先创建持久执行记录，成功与失败都会留痕，成功结果可追溯到
  批次、输入表、结果表和记录数；
- 模型版本审批对象已改为 `sq_flow_definition + sq_flow_version` 形成的不可变组合模型版本，审批只核对
  DAG 结构、节点固定版本、内容摘要、结构校验和发布责任。新增 `sq_model_version_approval`、提交/审批接口，
  并将历史 `CANDIDATE/VALIDATED` 流程版本迁移为待审批；新保存的流程版本会自动提交模型版本审批；
- 模型版本审批页面已删除模型类型、算法、训练数据、验证方式和机器学习效果指标。算法、训练数据与指标只在
  `operator_type = ML` 的机器学习算子版本详情中展示并审批；发布机器学习模型时会把相关治理快照写入不可变
  算子版本，避免审批时读取后续可能变化的训练记录；
- 后端全量 Maven 测试 182 项通过，0 失败、0 错误；前端生产构建通过；部署后 frontend、backend、
  dm8、redis 全部 healthy。浏览器已验证 Header 调度管理、组合模型审批列表，以及 ML 算子详情中的质量评估区。

### 7.5 2026-09-04 调度任务管理与独立穿透式监控视图

- “调度任务”与“执行记录”已经按领域对象拆开：调度任务是可编辑的长期定义，执行记录是每次触发产生的
  不可变运行实例，不再使用两个名称近似、功能重复的页面；
- 新增数据库驱动的 `sq_schedule_task`，保存任务名称、任务类型、模型、Cron、输入表、输出表、过滤条件、
  启停状态、上次/下次执行时间和运行结果；前端支持新建、编辑、暂停、恢复、删除和按任务查看执行记录；
- 调度轮询改为直接读取启用状态的 `sq_schedule_task`；模型执行记录新增 `schedule_task_id`，可以从运行实例
  反向追溯具体调度任务。删除任务只软删除定义，历史执行记录继续保留；
- 左侧导航在“穿透式监控模型”下拆成两个独立入口：“调度任务与执行”和“穿透式监控视图”。输出结果不再
  与调度管理混在同一个页签，而是在“穿透式监控视图”中按一项输出一张卡片展示；
- 输出卡片首屏展示任务/流程名称、运行状态、输出名称和类型、记录数、正式运行与流程版本、经过的模型、
  所有者、读取权限、容量和生成时间；点击卡片可以继续查看节点执行路径、模型血缘和权限控制后的结果明细；
- 监控页面主体已改为弹性宽度，宽屏不再只占固定窄列；卡片网格使用 `auto-fill + minmax` 自适应列数，
  1180px 以下收缩统计卡列数，760px 以下切换为单列输出卡片；
- 穿透式监控仍只接收正式调度产物：试运行、沙箱整形、预览和手工预测均被排除。当前数据库没有正式调度
  输出时，卡片页显示真实空状态，不会拿测试预览产物填充；
- 后端全量 Maven 测试 184 项通过，0 失败、0 错误；前端生产构建通过。

### 7.6 2026-09-04 前端信息层级、侧栏与滚动重构

- Header 一级导航仅保留编号和中文模块名，删除装饰性英文副标题；各大模块右侧删除重复的模块标题、英文眉题、
  说明段落和返回入口，页面直接从真实操作区、统计区或功能页签开始；
- AI 工作台删除重复顶部标题和“新建会话”按钮，空白聊天框直接发言即可创建会话；欢迎区能力标识改为中文，
  保留简短能力名称和示例问题；
- 非 AI 模块左侧导航改为 252–300px 自适应单列菜单，恢复无文字的蓝色抽象装饰图形；AI 工作台继续使用较宽
  侧栏承载场景、数据源和历史会话。窄屏使用抽屉侧栏，矮屏自动缩小装饰图形；
- 模型中心的“模型数据源筛选”已上移到“模型资产 / 流程编排”功能栏右侧，统计卡片紧接下一行，不再夹在
  内容区中间；开发中心流水线页面统一使用“算子训练流程”口径；
- 算子类型的装饰字母改为“数、规、学、智、出”，场景分类与角色代码在页面中显示中文名称；技术标识、
  资源编码和数据库实际数据不做伪翻译；
- 工作区主体开启纵向滚动，算子卡片网格、治理表格、对话消息和长列表保留各自可滚动区域；浏览器实测
  算子库 10 张卡片时滚动容器 `scrollHeight` 大于 `clientHeight`；
- 前端生产构建通过，2370 个模块完成转换；仅保留主包大于 500 KiB 的既有非阻塞分包警告。

### 7.7 2026-09-04 全站统一为存储与运行风格

- 以“模型中心 → 模型治理 → 存储与运行”为统一视觉基线：页面使用 `#f5f7fa` 浅灰底、白色内容卡、
  `#e4e8ef` 细边框、8–12px 小圆角和轻量阴影，蓝色仅用于主操作、选中状态和关键数字；
- Header、左侧分区导航、数据中心、AI 工作台、开发中心、模型中心、穿透式监控、平台配置和登录页均已
  去除大面积渐变、玻璃模糊、重阴影与悬浮位移，统计卡、筛选栏、表格、构建器和空状态采用一致层级；
- 算子库和穿透式监控卡片改为紧凑白色治理卡，输出/策略/规则构建器采用双栏白色面板与浅灰分组块；
  模型成品卡、聊天场景卡、弹窗头部、输入框和按钮同步使用相同边框、圆角与状态色；
- 保留左侧低对比度抽象装饰以缓解空白，但降低透明度和视觉权重；修复全局主按钮样式覆盖
  `plain` 按钮后文字不可见的问题；
- 前端生产构建通过，2370 个模块完成转换。部署后已在浏览器逐页检查数据中心、AI 工作台、算子库、
  输出算子构建器、模型治理、穿透式监控视图和平台配置；各页背景、卡片、边框与导航状态一致。

### 7.8 2026-09-04 算子构建器与卡片重构

- 算子库通用卡片改为紧凑的业务信息卡：以类型、名称、用途、当前版本和运行环境为主信息，技术编码不再
  占据标题区域；用细色条区分五类算子，并将管理、加入流程收束为卡片底部操作；
- 机器学习算子卡片单独设计：保留分类、回归、聚类和异常检测的原有图标，集中展示训练数据、当前版本、
  训练结果、制品状态和常用操作；卡片支持多列到单列的响应式变化；
- 机器学习构建器页签“算子管理”已改为“机器学习算子管理”；训练流程中的数据接入、预处理、缺失值处理、
  特征工程、模型训练、效果评估和输出写入图标全部保留，算法目录及算法模板弹窗继续显示数据库中的算法图标；
- 数据、规则、智能体和输出构建器将运行环境、样例数据、Schema 和源码等技术项收进高级/技术详情区域，
  新建时由平台生成技术编码，默认流程只呈现业务目标、检查、试用和提交审批；
- 前端生产构建通过，2370 个模块完成转换；已更新本地 Docker 前端并在浏览器核验算子库卡片、机器学习
  算子卡片、页签命名以及训练流程节点和算法图标。

### 7.9 2026-09-04 全流程调度、穿透下钻与算子验收

- 现有 QA 算子、模型、流程、草稿和运行记录全部保留；未执行测试数据清理；
- 调度任务现可选择单个机器学习模型或已发布的完整流程版本，流程任务固定版本与 JSON 输入快照，正式运行
  写入 `schedule_task_id`；调度管理新增“立即执行”，人工验收不会改变原定下次执行时间；
- `QA_客户风险全链路_正式调度` 已产生正式运行并完成
  `DATA -> ML -> RULE -> AGENT -> EXCEL + LEAD`，6 个节点全部成功，正式产物可回溯任务、流程版本、
  随机森林模型、算子版本与运行时；
- 穿透详情新增真实 DAG 链路图；点击节点可读取冻结的运行输入、上游输出、节点输出和配置快照。结果明细支持
  服务端字段白名单筛选、排序和签名游标分页；浏览器已核验 6 节点分支图及机器学习节点输入快照；
- 新建数据库驱动的 `qa_model_reviewer` 测试账号；普通审批人仍禁止自审，系统管理员凭全局资源权限和对应
  审批权限可审批自己创建的模型、算子及输出能力版本，审批人、时间和意见仍完整留痕；
- ECharts 6.1.0 已完成依赖申请、管理员审批、SHA256/许可证/漏洞证明、运行时档案登记和算子版本绑定；
  对话生成的首个重复支付输出草稿因非法聚合被沙箱正确拒绝并保留，第二个草稿经 `count` 聚合修正后完成
  整形、5 行预览、管理员审批和真实运行；
- `QA_重复支付多视图_正式调度` 已产生正式运行：同一次运行生成运行制品、可信表格、ECharts 图表和组合页面
  四个就绪产物；DG-001 三条与 DG-002 两条记录分组展示，服务端按 `duplicateGroupId=DG-001` 筛选返回 3 条；
- 机器学习治理改为按任务类型评估：分类/回归继续要求 CV、OOS 或时间验证，KMeans 使用簇数与轮廓系数，
  Isolation Forest 使用评估样本量与异常占比。随机森林、线性回归、KMeans、Isolation Forest 均已发布为
  机器学习算子并真实预测成功；
- 前端生产构建通过；后端最终 Maven 测试为 186 项通过、0 失败、0 错误；backend、frontend、dm8、redis
  均为 healthy。后端容器已固定 `Asia/Shanghai` 时区。

## 8. 权限与安全约束

### 8.1 必须保持的边界

- 普通用户只能访问本人模型、流水线、算子、流程、运行、线索和输出；管理员可查看全部；
- Controller、Service、异步任务和 Agent Tool 都必须检查资源归属；
- 普通审批人不能审批自己创建的模型、算子或输出能力版本；系统管理员可应急自审，但必须保留完整审计记录；
- 算子版本和流程版本一经创建不得修改或删除，只能废弃；
- 运行时与依赖被引用时只能废弃，不能物理删除；
- 规则 Python 必须在无网络、只读根文件系统、能力移除和资源受限的沙箱中运行；
- SQL 和智能体节点不能通过画布配置覆盖版本锁定的白名单和预算；
- 线索与输出必须保留原始输入快照和证据；
- 不要恢复运行时临时安装依赖、stdout 解析业务结果或失败后伪造成功结果等旧行为。

### 8.2 密钥与上线前阻断项

- `.env` 含本机敏感配置且已被 Git 忽略，禁止提交或打印；
- `backend/src/main/resources/application.yml` 的硬编码 GLM Key 默认值已移除，当前仅从 `GLM_API_KEY`
  环境变量读取；历史中暴露过的 Key 仍必须立即轮换，并评估清理 Git 历史；
- 生产环境必须替换默认管理员密码、DM8 密码和 JWT 密钥；
- `OUTPUT_QUERY_CURSOR_SECRET` 应使用独立随机密钥，不建议生产环境继续回退到 JWT 密钥；
- 外部 Runtime Builder 必须配置至少 32 字节的 `RUNTIME_BUILDER_HMAC_SECRET`，并配合 nonce、时间窗、
  lease token、SBOM、provenance、许可证和漏洞扫描门禁；
- 不要在生产业务进程中授予镜像仓库写权限；镜像构建应由独立 CI/Worker 完成。

## 9. 已知问题与未完成工作

按优先级建议处理：

1. **P0：轮换历史中暴露过的 GLM Key**，检查远程仓库暴露范围并评估清理 Git 历史；
2. **P0：补齐生产密钥和权限配置**，包括 JWT、输出游标、Runtime Builder HMAC、数据库凭据；
3. **P1：接入企业 CI/Runtime Builder**，完成依赖审批后的自动镜像构建、扫描、签名和回调；
4. **P1：使用正式数据库迁移工具**，逐步替代大型启动时 Schema Seeder；
5. **P1：在目标 DM8/MySQL 数据规模上压测** DAG 执行、结果索引、游标分页、归档和恢复；
6. **P1：扩展真实业务全链路验收**：基础的 DATA -> ML -> RULE -> AGENT ->
   EXCEL + LEAD 已通过；尚需补充原始输入展开、节点回放、取消和超时场景；
7. **P1：验证多实例故障恢复**，覆盖租约过期、进程中止、重复回调和最终提交 fencing；
8. **P2：前端路由懒加载和代码分包**，降低主 bundle 体积；
9. **P2：更新根 `README.md` 和 `CLAUDE.md`**，其中仍有旧页面结构、旧部署和旧风险描述；
10. **P2：将 V2 Schema 和 API 纳入持续集成**，保留 DM8/MySQL 双数据库兼容测试。
11. **P1：接入输出外部适配器与组件沙箱**，完成数据库/对象存储/Webhook 的真实连接器，以及
    TypeScript 编译、测试、扫描、签名、审批和隔离 iframe 发布链路；完成前不得启用对应能力。

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
