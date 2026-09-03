# 算子编排 V2 架构

## 目标

V2 在不破坏 `/api/v1/mining/**` 的前提下，逐步引入统一算子、不可变版本、真实
DAG、标准线索和后续的沙箱/发布治理能力。当前已完成控制面，以及一条受限的试运行
纵切；不会让 LLM 或普通 API 绕过审批直接发布生产版本。

## 当前已实现

### 页面信息架构

“编排与治理”包含四个一级功能：调度中心、模型流水线、算子库、治理中心。原一级“流程编排”下沉为
调度中心和模型流水线中的二级页签：从模型成品进入时注入已审批的机器学习算子版本，从流水线草稿进入
时携带草稿来源上下文打开统一 DAG。运行结果不设独立一级页面，而是从流程编排的“运行记录”进入具体
运行实例，在实例详情中查看节点执行状态、线索及其原始输入，以及图表、表格和类 Excel 可视化结果。

调度中心只展示已发布并固化制品的模型，负责定期重训、定期预测和生产编排；模型流水线保存仍可编辑的
训练草稿，并保留独立的训练草稿编辑入口。普通用户只能查看和操作本人模型、流水线及算子版本，管理员
可查看全部资源；模型成品进入统一 DAG 前还必须拥有已审批发布的机器学习算子版本，前端不会仅凭模型
ID 绕过版本审批，后端继续执行资源归属校验。算子库使用数据加工、规则、机器学习、智能体和输出五个
类型子页签切换目录，不再以难以辨认的紧凑筛选按钮承担主分类。

### 统一算子与不可变版本

算子定义支持 `DATA`、`RULE`、`ML`、`AGENT`、`OUTPUT` 五种类型。定义保存稳定身份，
`sq_operator_version` 保存不可变快照。快照对 JSON 对象键排序后计算 SHA-256，重复内容
会返回已有版本；API 不提供版本更新或删除操作。

流程采用相同方式分离 `sq_flow_definition` 与 `sq_flow_version`。流程版本节点必须绑定
确切的 `operatorVersionId`，不能引用“最新版”。

### 可对话改造的规则算子

规则算子不再被限定为“从固定积木库组合”。主要路径是：用户在已有会话中描述业务规则，
系统读取最近对话和指定的上一版本，让 LLM 生成完整的 Python 纯函数工件、输入输出 Schema、
参数 Schema、正常/边界测试和解释，先保存到 `sq_rule_draft`。继续对话修改时创建新草稿，
不会覆盖旧草稿或旧版本。确认后再转成不可变的 `SANDBOX_EXTENSION` 候选版本。

生成规则必须保持 `evaluate(records, parameters)` 契约及 `sourceRef` 血缘。生成与保存阶段绝不
执行代码；验证和试运行由独立 Python Docker 沙箱执行，关闭网络、只读根文件系统、移除 Linux
capability，并限制内存、CPU、PID 和临时目录。只有正常/边界测试及血缘检查全部通过，草稿才可
转成 `SANDBOX_EXTENSION` 候选版本并提交人工审批，不能由作者直接发布。

`RULE_DSL` 保留为可选的低代码实现，不再是规则自由度的上限。其内置原语包括：

首批规则原语：

- `filter`、`compare`、`text_match`、`derive`；
- `group_by`、`aggregate`、`time_window`、`lookup`、`rank`；
- `sequence`、`threshold`、`lead_output`。

规则组合校验返回能力覆盖率、缺失能力、参数错误、警告和执行层级。未知能力会明确
失败，不会被当作可执行规则。

试运行的 `RULE_DSL` 执行器采用白名单解释器，支持上述 12 种原语的安全形式。其中：

- `derive` 使用 JSON 表达式树，不执行字符串脚本；
- `lookup` 当前只允许版本内固化的内联字典，不连接任意外部数据源；
- `text_match` 支持 `any/all/none`，正则在沙箱 Worker 完成前明确拒绝。

```json
{
  "steps": [
    {
      "id": "match",
      "op": "text_match",
      "config": {
        "field": "content",
        "mode": "any",
        "keywords": ["投诉", "欺诈"]
      }
    },
    {
      "id": "window",
      "op": "time_window",
      "config": {"timeField": "event_time", "range": "30d"}
    },
    {
      "id": "count",
      "op": "aggregate",
      "config": {
        "metrics": [{"name": "hit_count", "function": "count"}]
      }
    },
    {
      "id": "hit",
      "op": "threshold",
      "config": {"expression": "hit_count >= 3"}
    }
  ]
}
```

### 一等输出算子与可视化结果

输出是第五类算子 `OUTPUT`，统一实现类型为 `OUTPUT_RENDERER`，首批输出种类为：

- `LEAD`：声明线索类型、命中条件、主体/决策/来源字段映射；
- `CHART`：声明图表类型、维度、指标、标题等内容规格；
- `TABLE`：声明展示列、别名、格式和排序；
- `EXCEL`：声明类 Excel 网格、列、格式和内容要求；它是界面展示，不生成下载文件。

一个规则或模型结果可以并行连接多个输出，例如同时生成线索、风险分布图和 Excel 明细。
`LEAD` 与其他输出算子一样按需使用，流程编译器不会自动追加。流程可以只生成图表或表格，
也可以暂不配置输出算子；需要形成线索时，显式添加一个已发布的 `LEAD` 输出算子。

每个输出节点会在 `sq_output_artifact` 记录内容规格和摘要，在 `sq_output_artifact_row` 保存
展示结果、原始输入、判断依据和血缘引用，并在 `sq_output_artifact_cell` 建立可查询的标量字段索引。
流程运行实例详情统一渲染线索、图表、数据表和类 Excel 网格；每一行均可展开核对对应的原始输入。
模型预测值、预测概率与预测前输入因此可以
同屏展示，重复支付等多输入判断也可展开查看所有关联订单。

输出算子版本确定输出种类，流程节点配置确定本次内容要求。例如 Excel 节点可配置：

```json
{
  "contentSpec": {
    "sheetName": "预计逾期客户",
    "columns": [
      {"field": "customerName", "title": "客户"},
      {"field": "overdueProbability", "title": "逾期概率", "format": "0.00%"},
      {"field": "contractAmount", "title": "合同金额", "format": "#,##0.00"}
    ],
    "sort": [{"field": "overdueProbability", "direction": "desc"}]
  }
}
```

### 大结果筛选、排序与游标分页

正式输出提交时，结果行、标准线索和标量字段索引在同一事务中写入；任一环节失败都会回滚整次提交，
不会出现“产物可见但查询索引缺失”的半完成状态。索引服务将单条来源快照与公开结果字段合并，结果字段
优先，并把嵌套对象展开为点路径；数组和复杂对象仍完整保存在行详情中，但不作为可查询字段。

查询字段目录完全来自服务端实际索引，客户端不能自行声明字段。稳定的字符串、数值和布尔字段可以筛选
及排序，混合类型字段只支持空值判断。筛选操作符限制为 `EQ/NE/CONTAINS/STARTS_WITH`、
`GT/GTE/LT/LTE/BETWEEN/IN/IS_NULL/NOT_NULL`，并限制条件数、`IN` 项数和值长度；字段名和筛选值始终通过 JDBC 参数绑定，
SQL 中只有服务端枚举出的别名、列名和比较符。字符串精确匹配同时校验 SHA-256 与完整文本，避免只用
摘要产生碰撞误判。

排序采用“一个业务字段 + 行号稳定决胜”，空值统一排在末尾；默认按行号排序。翻页使用 HMAC-SHA256
签名的键集游标，游标绑定产物 ID、筛选/排序/页大小指纹、最后行号和签发时间，不携带业务字段值，默认
24 小时过期，因此不能篡改、跨产物或跨查询复用。字符串排序键最长 1000 字符以兼容 MySQL 与 DM8；
完整字符串仍用于精确匹配和包含匹配。

旧产物的 `query_index_status` 为 `LEGACY`，仍可按行号游标查看并展开原始输入，但不能进行业务字段筛选
或排序；重新运行固定流程版本会生成 `READY` 索引。原有基于页码的 GET 接口保留给旧调用方，结果中心与
V2 DAG 查看器使用新的 POST 查询协议。

### 对话创建输出算子

输出算子不能再通过普通版本 API 直接创建。完整发布链为：

1. **对话生成草稿**：LLM 根据最近对话、上一版本和输入 Schema 生成 `sq_output_draft.raw_spec`；
2. **沙箱整形**：平台只保留白名单声明式字段，拒绝脚本、SQL、HTML、URL、模板、formatter 和
   任意原生 ECharts 配置，并强制开启原始输入与证据展开区；
3. **预览验证**：在最多 100 条、默认 512 KiB 的样例上运行正式 `OUTPUT_RENDERER`，检查列字段、
   图表数值指标、标准线索策略和血缘完整性，返回与结果中心一致的视图模型；
4. **提交审批**：只有 `PREVIEW_VALIDATED` 草稿可生成不可变候选版本并进入人工审批；审批通过后
   才切换为 `PUBLISHED`。已提交草稿不能修改，继续对话会创建新草稿。

生命周期为：

```text
GENERATED -> SHAPED -> PREVIEW_VALIDATED -> PENDING_APPROVAL -> PUBLISHED
               |              |                    |
        SHAPING_FAILED   PREVIEW_FAILED      APPROVAL_REJECTED
```

预览全量数据直接返回调用端；数据库中的草稿预览会自动截断到可移植 `TEXT` 大小，验证哈希和报告
仍完整保存。正式运行结果使用独立明细表分页存储。

### 模型算子

从统一算子库新建 `ML` 算子时直接进入数据挖掘页面并打开新建模型窗口。模型完成训练和发布后，
系统以 `ml_model_<modelId>` 创建或复用算子定义、生成绑定固定模型制品和运行时的不可变候选版本，
并自动提交到统一版本审批；审批通过后才会出现在 DAG 已发布算子目录中。

`MINING_RUNTIME` 已接入现有模型运行时。编排节点调用经过模型归属校验的无副作用预测入口，不提前
写预测历史或结果表；预测值、概率和 `MODEL_PREDICTION` 证据追加到记录，平台血缘字段保持不变。
整条 DAG 成功后才统一提交线索与可视化输出。

### 受权 SQL 数据算子

`DATA/SQL_AST` 已注册为只读数据入口。算子版本固定 SQL、数据源、表白名单、命名参数、最大行数、
查询超时和生成来源引用所需字段，运行时不能由画布节点替换这些策略：

```json
{
  "dataSourceId": 6,
  "sql": "SELECT order_id, supplier, amount FROM payment_order WHERE paid_at >= :startTime",
  "allowedTables": ["payment_order"],
  "defaultParameters": {"startTime": "2026-01-01T00:00:00Z"},
  "sourceRefFields": ["order_id"],
  "maxRows": 1000,
  "timeoutSeconds": 30
}
```

发布前与每次运行前均重新解析 SQL，只接受一条 `SELECT`，拒绝注释、多语句、位置参数、
`SELECT INTO`、`FOR UPDATE` 和表白名单外访问。数据源必须已启用且允许问数，运行时只合并
已声明的命名参数值，并使用独立 JDBC 查询限制执行超时和返回行数。

每一行查询结果同时写入 `__sourceRefs` 和 `__sourceSnapshots`；后续即使经过规则、模型、智能体和
字段投影，最终线索或 Excel 网格仍可展开 SQL 查询得到的原始输入。`SQL_AST` 是数据源节点，
因此必须位于 DAG 根部且不能接收上游连线。

### 受控智能体算子

`AGENT/AGENT_POLICY` 使用独立的有界智能体循环。版本固定模型、系统指令、只读工具白名单、
数据源/表范围、输出字段以及轮数、工具调用数、输入记录数和总 Token 预算。例如：

```json
{
  "model": "glm-5.1",
  "instruction": "核对订单关联信息并给出风险判断",
  "allowedTools": ["execute_sql"],
  "dataSourceId": 6,
  "allowedTables": ["payment_order", "supplier"],
  "responseField": "agentDecision",
  "traceField": "agentToolTrace",
  "maxTurns": 3,
  "maxToolCalls": 4,
  "maxInputRecords": 20,
  "maxTotalTokens": 8000
}
```

发布时只允许已启用、只读且非破坏性的平台注册工具；运行时再次检查白名单，移除模型提供的
数据源和过滤器选择，再注入版本锁定的数据源/表范围。模型不能临时安装依赖、调用写工具或扩大
数据访问范围。任一记录没有在预算内生成最终文本，或工具失败且策略要求失败关闭时，节点失败。

智能体在原记录上追加判断字段、精简工具轨迹和 `AGENT_DECISION` 证据，不替换输入记录；因此它的
结果同样满足统一线索契约，能在结果中心同时展示智能体判断、工具执行摘要和对应原始输入。

### SQL 与智能体对话式发布工作台

`SQL_AST` 与 `AGENT_POLICY` 已关闭普通版本 API 的直接发布入口，统一通过 `sq_policy_draft` 执行：

```text
对话生成草稿 -> 策略整形 -> 真实运行时预览 -> 提交不可变版本审批 -> PUBLISHED
                    |               |                       |
             SHAPING_FAILED    PREVIEW_FAILED        APPROVAL_REJECTED
```

两类草稿共用生命周期，但安全边界不同：

- SQL：用户先选定数据源与表白名单；LLM 只生成白名单内的查询，返回内容中的数据源和表范围会被丢弃。
  整形阶段重新进行 AST、只读语句、命名参数和表范围校验；预览最多返回 50 行且超时不超过 15 秒，
  使用正式 `SQL_AST` 执行器生成来源快照。
- 智能体：`GET /api/v2/agent-tools` 只公开已启用、只读且非破坏性的工具。用户选择的工具、数据源、
  表范围和生产模型由服务端固定；LLM 只能生成任务指令、输出字段与执行预算。预览使用正式有界智能体
  执行器，最多处理 10 条样例，并保留每条记录的判断、工具轨迹、原始输入和证据。

整形只复制平台允许的规范字段，未知字段全部丢弃，并绑定 `DATA_CONNECTOR` 或 `AGENT_GATEWAY`
固定运行时。只有状态为 `PREVIEW_VALIDATED` 的草稿可以提交审批；版本载荷保存整形报告、预览报告、
运行时档案和草稿来源。前端“结果与算子治理 → SQL / 智能体”提供完整操作界面，预览统一以类 Excel
网格展示，每行都能展开来源与判断依据；发布后可直接将精确版本加入 V2 DAG。

### 规则工作台与版本人工审批

“结果与算子治理 → 规则设计”补齐了自由规则的完整前端链路：创建 `RULE` 定义、结合当前会话生成
完整 Python 规则、查看源码与正常/边界测试、选择固定 `RULE_PYTHON` 运行时、执行隔离沙箱测试，
通过后创建不可变 `SANDBOX_EXTENSION` 版本并提交审批。缺少包时仍进入 `DEPENDENCY_MISSING`，
不能绕过依赖中心。

所有普通候选版本采用统一生命周期：

```text
CANDIDATE / VALIDATED -> PENDING_APPROVAL -> PUBLISHED
                                  |
                               REJECTED
```

审批记录保存在 `sq_operator_version_approval`，包含申请人、申请说明、审批人、意见、时间和版本引用。
`operator_reviewer` 或 `admin` 可以查看全局队列并审批，普通用户只能查看自己的申请；即使管理员是版本
创建人也不能自审。审批采用数据库条件更新，多个审批人并发处理时只有第一个决定生效。审批只能改变
生命周期状态，不能修改版本源码、SQL、工具白名单、Schema、运行时档案或镜像摘要。被驳回版本保持
不可变，作者必须继续对话并生成内容不同的新版本。DAG 算子目录仍只返回 `PUBLISHED` 版本。

### DAG 预检与执行

DAG 根据真实 `edges` 执行环检测和拓扑分层，同时检查：

- 节点 ID、重复节点和不存在的边端点；
- 节点是否绑定确切算子版本；
- 自环、重复边、互不连通子图和多入口；
- 未发布算子版本的归属权限。
- 每条边两端精确算子版本的输出/输入 JSON Schema、必填字段和字段类型；
- `MERGE/PROJECT` 映射模式、字段路径、重复目标和平台保留字段覆盖。
- `SQL_AST` 只能作为根节点，SQL 与智能体的版本策略不能被节点配置覆盖。

预检返回 `topologicalOrder`、`executionLevels` 和逐边 `edgeContracts`。执行引擎已按该层级运行，
同层并行、跨层等待。

### DAG 边数据契约

边不再只表示执行先后关系，也保存不可变的记录转换契约：

```json
{
  "source": "overdue_model",
  "target": "risk_excel",
  "mappingMode": "PROJECT",
  "fieldMappings": [
    {"from": "customer.id", "to": "customerId", "required": true},
    {"from": "predictionProbability", "to": "overdueProbability", "required": true},
    {"from": "contractAmount", "to": "contractAmount", "required": false, "defaultValue": 0}
  ]
}
```

- `MERGE`：保留上游完整记录，再按映射增加或改写字段；适合连续规则/模型加工；
- `PROJECT`：只保留映射得到的业务字段；适合向固定展示或接口契约收口；
- `required=true`：运行时任一记录缺少来源字段且没有 `defaultValue` 时立即失败；默认值同样受版本锁定；
- Schema 明确时，发布前阻止不存在字段、类型不兼容和未满足的目标必填字段；Schema 为空或只声明
  `records` 信封时返回警告，不伪造静态确定性；
- `integer → number` 允许安全拓宽，其余已声明类型必须兼容。

边映射在下游节点计算输入哈希之前执行。即使使用 `PROJECT`，平台也会绕过用户映射强制复制
`__sourceRefs`、`__sourceSnapshots` 和 `__evidence`，因此模型预测、规则筛选或字段投影之后生成的
线索/可视化结果仍能展开对应的全部原始输入与判断证据。

### 标准线索契约

线索、原始输入快照和证据分表存储：

- `sq_lead`：线索对象、得分、等级、流程版本、运行及处理状态；
- `sq_lead_source_snapshot`：判断时的原始参数与不可变哈希；
- `sq_lead_evidence`：规则条件、实际值、模型贡献或证据片段；
- `sq_lead_status_history`：后续人工处理状态审计。

`LeadService.recordLead` 是执行器写入边界；当前公共 API 只提供授权后的列表和详情读取，
避免普通用户伪造生产线索。

入口数据会被包装为平台血缘信封，每条记录包含不可变的 `__sourceRefs` 和
`__sourceSnapshots`。规则、模型、智能体和输出执行器都必须保留它们；节点一旦丢失血缘，
运行立即失败。模型可以在记录上增加 `overdueProbability`、`prediction` 等字段，`LEAD`
输出根据概率筛选，但线索详情展示的快照仍是预测前的原始贷款/客户参数。

### 试运行执行面

`POST /api/v2/flow-versions/{id}/trial-runs` 提交一个有界输入快照。默认限制 1000 条、
2 MiB；入口 `BUILTIN/run_input` 算子只转发快照，不访问数据库或网络。

执行引擎根据 DAG 预检产生的层级运行：同层节点使用独立节点线程池并行，下一层等待
当前层全部成功。任一节点失败后不再执行后续层。每个节点记录输入/输出哈希、结果摘要、
执行日志、耗时和错误；运行通过持久化事件总线提供 SSE 进度。

`OUTPUT/LEAD` 只产生待提交线索。只有整个 DAG 成功后，执行引擎才在同一事务中写入
全部线索和输出产物，避免失败运行留下半批线索或半批 Excel/图表规格。

一个最小输入为：

```json
{
  "records": [
    {
      "eventId": "E-1001",
      "companyId": "C-01",
      "companyName": "甲公司",
      "content": "收到客户投诉",
      "eventTime": "2026-09-01T08:30:00Z"
    }
  ],
  "referenceTime": "2026-09-01T09:00:00Z"
}
```

### 运行租约、恢复、取消与超时

每个 `QUEUED/RUNNING` 运行通过数据库原子更新取得 `leaseOwner + leaseToken + leaseExpiresAt`。
默认租约 30 秒、每 5 秒续租；同一时刻只有持有当前 fencing token 的实例可以更新节点或提交结果。
旧实例即使因网络分区继续计算，其节点更新和最终提交也会因 token 不匹配被拒绝。

应用启动及周期扫描都会认领无租约的排队任务和租约已过期的运行任务。节点快照用于用户发起的审计回放，
不作为自动跳过上游节点的恢复缓存，因此运行恢复仍采用确定性的整条 DAG 重算；旧节点行保留上次状态摘要并增加
`attemptNo`，随后绑定新 token。当前全部执行器均为无副作用计算、受权只读查询或延迟输出，业务写入只发生在
最终提交阶段。如果检测到旧版本曾完成业务提交但未更新运行状态，恢复器会直接收口为成功，不重复执行。

最终输出提交先以租约将运行切换到 `COMMITTING`，再在同一数据库事务中写入全部线索、可视化产物和
`SUCCESS` 状态；进程在任一点崩溃都会整体回滚。取消接口立即将运行置为 `CANCELED`、清除租约并
中断当前实例上的节点线程；其他实例在下一次续租时停止。失败或取消均不能提交半批结果。

流程节点配置可以声明 `nodeTimeoutSeconds`，范围为 1–3600 秒，未声明时默认 300 秒。该值随
不可变流程版本保存。超时后节点进入 `TIMED_OUT`，看门狗中断执行线程并使整个运行失败；最终提交
仍受租约门禁保护，因此即使底层驱动未立即响应线程中断，也不能晚到写回。

### 节点级可审计回放

新运行会在每个节点执行前，把映射后的上游输出、完整流程输入、节点配置、算子版本快照、流程/算子内容哈希、
固定运行时 ID 和镜像摘要写入 `sq_node_run_snapshot`；节点成功后再补充完整原输出。大字段使用 Base64 编码的
`TEXT` 分块保存在 `sq_node_run_snapshot_chunk`，默认单节点完整快照上限为 16 MiB。分块和主记录都绑定
`attemptNo + leaseToken`，过期执行线程不能覆盖新 attempt 的权威快照。

用户可以对自己已结束的 `SUCCESS/FAILED/TIMED_OUT` 节点创建独立 `sq_node_replay`。回放执行前会重新验证：

- 原流程版本仍存在且内容哈希一致；
- 原算子版本快照、当前版本记录和实现类型一致；
- 运行时档案、版本绑定及镜像摘要仍与原执行一致；
- 重新计算的节点输入哈希与原输入哈希一致。

任一检查失败都会关闭回放，不会追随新算子版本或新运行时。回放使用原运行的用户权限和原执行上下文，复用节点
超时上限，具有独立队列、租约、attempt、启动恢复和取消状态机。算子返回的线索草稿和输出工件不会进入最终提交，
所以回放不会制造新业务线索，也不会污染正式可视化结果。

成功回放会将输出按血缘字段 `__sourceRefs`（缺失时才回退到业务 ID 或行序号）与原输出匹配，显示完全一致、
新增、缺失、变化记录数，变化字段、指标差异以及两侧有界样本。SQL、ML 和智能体可能因只读外部数据、模型服务或
LLM 的当前状态产生合理漂移；系统保留这种漂移，而不是伪造确定性。旧版本部署前产生的节点没有完整快照，接口会
返回 409，需重新试运行一次对应流程后才能执行节点回放。

### 保留策略、可恢复归档与容量监控

输出产物和成功的节点回放分别记录 `payloadBytes`、`retentionUntil`、`usageAccounted` 和独立的
`archiveStatus`。默认策略为输出保留 90 天、回放保留 30 天；每个用户默认拥有 1 GiB 热数据配额和
5 GiB 归档配额，使用率达到 80% 时告警。管理员可在“结果与算子治理 → 存储与运行”修改策略；已写入
对象保留原截止时间，新对象和恢复对象使用更新后的策略。

容量不是事后统计门禁。DAG 最终输出提交在原事务中锁定 `sq_storage_usage` 用户账本并预留热容量；节点
回放通过独立提交服务把“容量预留、回放分块写入、SUCCESS 发布”放在同一事务。并发运行不能越过配额，
容量不足时不会留下半批线索、半个输出产物或成功但缺少明细的回放。

超过期限或管理员手动归档时，系统先锁定目标元数据，将输出配置、摘要、结果行和标量索引，或回放摘要、日志和输出分块，序列化为
版本化 JSON，使用 GZIP 压缩和 Base64 `TEXT` 分块写入 `sq_archive_record/sq_archive_chunk`，记录原始大小、
实际存储大小和 SHA-256。只有归档完整写入且归档容量足够，才删除热表明细并把目标标记为 `ARCHIVED`；
对象身份、固定版本绑定和归档审计信息始终保留。归档对象不会出现在普通结果查看器中；载荷版本 2 会把配置、摘要和日志正文一并移出热表，恢复逻辑仍兼容早期载荷版本 1。

恢复会在同一事务中校验归档格式、声明大小、SHA-256、目标 ID 和所有者，检查热容量后原样回填行、字段索引
或回放分块，再删除归档分块并重置保留截止时间。任何校验失败都会回滚，不能将损坏或跨对象载荷恢复到热表。

有界定时任务默认每小时扫描 25 个对象；应用首次启动还会分批为升级前数据补充保留期限和容量账目。目标行锁
保证多应用实例或手动操作并发时只有一个归档者。治理页同时展示用户热/归档容量、运行状态分布、最近运行、
排队时长、两分钟无心跳运行和过期租约，并提供手动扫描、归档及恢复操作。

## V2 API

```text
GET  /api/v2/rule-capabilities
POST /api/v2/rule-compositions/validate
POST /api/v2/dags/validate

GET  /api/v2/operators
POST /api/v2/operators
GET  /api/v2/operator-catalog             # 仅返回可见的 PUBLISHED 版本，供 DAG 画布使用
GET  /api/v2/operators/{operatorId}/versions
POST /api/v2/operators/{operatorId}/versions
GET  /api/v2/operators/{operatorId}/rule-drafts
POST /api/v2/operators/{operatorId}/rule-drafts/from-dialogue
POST /api/v2/operators/{operatorId}/rule-drafts/{draftId}/validate
POST /api/v2/operators/{operatorId}/rule-drafts/{draftId}/candidate-version

GET  /api/v2/operators/{operatorId}/output-drafts
POST /api/v2/operators/{operatorId}/output-drafts/from-dialogue
POST /api/v2/operators/{operatorId}/output-drafts/{draftId}/shape
POST /api/v2/operators/{operatorId}/output-drafts/{draftId}/preview
POST /api/v2/operators/{operatorId}/output-drafts/{draftId}/publish-version

GET  /api/v2/agent-tools                    # 仅生产可用的只读工具目录
GET  /api/v2/operators/{operatorId}/policy-drafts
POST /api/v2/operators/{operatorId}/policy-drafts/from-dialogue
POST /api/v2/operators/{operatorId}/policy-drafts/{draftId}/shape
POST /api/v2/operators/{operatorId}/policy-drafts/{draftId}/preview
POST /api/v2/operators/{operatorId}/policy-drafts/{draftId}/publish-version

GET  /api/v2/operator-version-approvals
GET  /api/v2/operator-version-approvals/capability
GET  /api/v2/operator-version-approvals/{approvalId}
POST /api/v2/operators/{operatorId}/versions/{versionId}/submit-approval
POST /api/v2/operator-version-approvals/{approvalId}/review

GET  /api/v2/flows
POST /api/v2/flows
GET  /api/v2/flows/{flowId}/versions
POST /api/v2/flows/{flowId}/versions

GET  /api/v2/runtime-capabilities
GET  /api/v2/dependency-requests
POST /api/v2/dependency-requests
POST /api/v2/dependency-requests/{requestId}/review
POST /api/v2/dependency-requests/{requestId}/deprecate
GET  /api/v2/runtime-profiles
POST /api/v2/runtime-profiles/register-build
POST /api/v2/runtime-profiles/{profileId}/deprecate
GET  /api/v2/runtime-build-jobs
GET  /api/v2/runtime-build-jobs/capability
POST /api/v2/runtime-build-jobs/{jobId}/retry
POST /api/v2/runtime-build-jobs/{jobId}/cancel
GET  /api/v2/operator-versions/{versionId}/runtime
GET  /api/v2/drafts/{draftType}/{draftId}/dependencies
POST /api/v2/flow-versions/{flowVersionId}/trial-runs
GET  /api/v2/flows/{flowId}/runs
GET  /api/v2/runs/{runId}
POST /api/v2/runs/{runId}/cancel
GET  /api/v2/runs/{runId}/nodes
POST /api/v2/runs/{runId}/nodes/{nodeRunId}/replays
GET  /api/v2/runs/{runId}/node-replays
GET  /api/v2/node-replays/{replayId}
POST /api/v2/node-replays/{replayId}/cancel
GET  /api/v2/runs/{runId}/outputs
GET  /api/v2/runs/{runId}/leads
GET  /api/v2/runs/{runId}/events            # SSE，支持 Last-Event-ID
GET  /api/v2/outputs                         # 当前用户最近可视化结果
GET  /api/v2/outputs/{artifactId}/view       # 兼容旧调用方的页码分页结果
POST /api/v2/outputs/{artifactId}/query      # 白名单筛选、稳定排序、签名游标分页

GET  /api/v2/storage-governance/dashboard
POST /api/v2/storage-governance/policy
POST /api/v2/storage-governance/outputs/{artifactId}/archive
POST /api/v2/storage-governance/node-replays/{replayId}/archive
POST /api/v2/storage-governance/archives/{archiveId}/restore
POST /api/v2/storage-governance/retention/run

GET  /api/v2/leads
GET  /api/v2/leads/{leadId}

# 外部 CI / 构建器接口（HMAC，不使用用户 JWT）
POST /api/v2/runtime-build-worker/jobs/claim
POST /api/v2/runtime-build-worker/jobs/{jobNo}/heartbeat
POST /api/v2/runtime-build-worker/jobs/{jobNo}/complete
```

业务接口沿用现有 JWT 拦截和用户上下文。只有 `runtime-build-worker` 三个接口使用独立 HMAC 身份；
未配置至少 32 字节的 `RUNTIME_BUILDER_HMAC_SECRET` 时，这些接口以 503 关闭。候选版本只有所有者
或管理员可引用；发布后再按授权目录开放复用。

## 固定运行时与依赖中心

每个算子版本都通过 `sq_operator_version_runtime` 绑定一个不可变 `runtimeProfile` 和镜像摘要。
运行前会再次校验绑定、运行时类型、生命周期和依赖锁；规则与 ML 执行器实际使用绑定档案的
镜像引用，不会退回草稿或全局配置中临时指定的镜像。

运行时家族保持隔离：`PYTHON_PACKAGE → RULE_PYTHON`、`ML_ALGORITHM → ML_MODEL`、
`JDBC_DRIVER → DATA_CONNECTOR`、`AGENT_TOOL → AGENT_GATEWAY`、
`FRONTEND_RENDERER → OUTPUT_RENDERER`。跨家族依赖会在申请、版本创建和运行前被拒绝。

规则草稿会从 Python import 推断依赖，输出草稿可声明渲染器依赖。默认或选定档案不具备依赖时，
草稿进入 `DEPENDENCY_MISSING`，既不能执行也不能发布，并在 `sq_draft_dependency` 保留可审计关联。
依赖中心支持用户申请以及管理员的来源、精确版本、SHA-256、许可证、高危/严重漏洞门禁。

批准本身不安装任何软件。批准事务会按关联草稿聚合相同运行时家族的完整依赖集合，在
`sq_runtime_build_job` 创建唯一 `QUEUED` 任务。后批准的申请若形成更完整的依赖集合，会取消尚未领取的
子集任务；因此同时需要多个 Python 包或智能体工具的草稿会得到一个包含完整依赖闭包的运行时，而不是
多个互不兼容的单包镜像。历史 `APPROVED` 申请会在启动时自动补建任务。

企业 CI 通过拉取协议领取任务。平台返回不可变构建规范、基础镜像摘要和一次性租约；任务支持心跳续租、
租约过期回队、有限自动重试以及管理员最终重试/取消。业务进程不持有 Docker 或制品库写权限。
所有 worker 请求按以下原文计算 HMAC-SHA256，并把摘要放入 `X-SQ-Build-Signature`：

```text
timestamp + "\n" + nonce + "\n" + method + "\n" + path + "\n" + rawBody
```

时间戳默认只允许 300 秒偏差，nonce 持久化到 `sq_runtime_build_nonce` 并全局去重，回调还必须携带领取时
获得的 `X-SQ-Build-Lease`。成功回调只接受 `@sha256` 固定镜像、HTTPS/OCI SBOM 与 provenance 地址、
对应摘要、已验证来源、批准许可证以及零高危/严重漏洞；worker 不能替换审批确定的依赖 ID、基础运行时、
档案代码或名称。

回调通过后形成新的 ACTIVE 档案，关联的 RULE/OUTPUT/POLICY 草稿会自动选择新运行时重跑沙箱或安全整形。
该动作不会替用户执行样例预览，也不会提交版本审批；发布仍创建新的算子版本，不修改已有版本。
`register-build` 作为应急手工入口保留，但会拒绝覆盖已被外部 worker 领取的任务，并执行相同的草稿重验。

运行时或依赖没有删除接口。废弃会阻止新版本继续绑定，但已绑定版本仍按原摘要运行，满足审计回放。

## V2 DAG 编排画布

主界面的“编排”入口提供独立 V2 DAG 画布，不复用旧挖掘 Pipeline 的线性节点模型。左侧算子面板
只加载当前用户可见的 `PUBLISHED` 算子版本；每个画布节点保存精确 `operatorVersionId`、节点配置和
布局坐标。拖入同一算子的其他发布版本会形成不同节点，不存在运行时自动追随“最新版”的行为。

画布支持任意 DAG 连线、环路即时阻断、节点移动、边删除、服务端拓扑预检和执行层级预览。
每条连线显示映射模式和字段数；选中连线可查看两端 Schema 字段、配置 `MERGE/PROJECT`、字段映射与
运行时必填门禁。服务端校验后，兼容边显示绿色，不兼容边显示红色并列出精确原因。
`SQL_AST` 节点直接显示数据源、表数量和行数上限，并在前端取消输入端口；智能体节点显示固定模型、
只读工具数量与执行预算。检查器可展开完整表/工具白名单，相关策略只读且随算子版本锁定。
输出算子显示 `LEAD/CHART/TABLE/EXCEL` 类型，并被前后端共同限制为终点。对话生成的已发布输出版本
不能在节点配置中覆盖 `contentSpec` 或 `leadPolicy`，避免绕过“沙箱整形 → 预览验证 → 提交审批 → 审批发布”门禁。

保存会创建新的不可变 `sq_flow_version`。试运行绑定该流程版本，画布轮询展示节点状态；整条 DAG
成功后读取正式提交的输出产物，并通过服务端查询状态驱动结果查看器，展示图表、表格或类 Excel 网格及其
原始输入、证据和血缘；大结果集不会再一次性载入浏览器。规则、输出、SQL 和智能体版本审批通过后，都可
从治理工作台点击“加入 V2 DAG”直接进入画布。
试运行面板显示执行次数和恢复次数，运行期间可主动取消，并区分 `CANCELED`、`TIMED_OUT` 等状态。
运行结束后，可从任一已执行成功、失败或超时的节点发起审计回放；详情弹窗展示固定版本/运行时/输入哈希、
输出一致性卡片、按血缘匹配的记录差异、变化字段，以及原输出和回放输出的并排样本。界面只做可视化审计，
不提供下载文件，也不会把回放输出发布为新的正式产物。

## 持久化与兼容性

项目当前没有启用 Flyway，V2 表由 `OrchestrationV2SchemaSeeder` 以可重复执行的
`CREATE TABLE IF NOT EXISTS` 建立，并通过可重复执行的加列语句升级已有 V2 运行表；DDL 保持 MySQL
与 DM8 兼容模式可用。最终阶段新增 `sq_storage_policy`、`sq_storage_usage`、`sq_archive_record` 和
`sq_archive_chunk`，并为输出产物及节点回放增加归档、容量和保留截止字段。此前的输出标量索引、节点快照、
快照分块、节点回放和回放输出分块继续保留。没有修改现有 V1 挖掘表，V1 继续运行。

## 阶段状态

图示目标对应的架构改造阶段已经全部完成。后续工作属于上线工程，包括在目标 MySQL/DM8 环境执行数据量压测、
接入企业对象存储或冷存储介质、配置生产密钥和告警出口，不再改变当前算子、线索、输出和归档契约。

## 验证

Docker 后端构建会执行完整 Maven 测试：

```bash
docker build --target java-build -t smart-query-v2-compile-check backend
```

当前 153 个测试覆盖内容哈希、DAG 环/并行层级、重复边歧义、边 Schema 兼容、字段映射与血缘保留、输出版本与终点约束、规则能力缺口、运行时依赖锁、多依赖聚合、构建任务租约、HMAC 防重放、安全制品回传、执行器注册、安全输入转发、
关键词筛选、分组计数、阈值、血缘丢失防护、模型预测转线索、输出整形沙箱、发布门禁、
图表输出规格、标准线索映射、SQL 单语句/表白名单/参数门禁、数据源授权、SQL 原始输入快照、
智能体只读工具白名单、对话草稿权限钉死、策略预览发布门禁、策略覆盖阻断、预算循环、智能体证据保留、运行租约抢占、过期恢复、
取消门禁、节点超时范围、本地中断控制、最终提交事务栅栏、版本审批状态迁移、禁止创建人自审、节点快照分块、
回放权限与固定绑定复制、按血缘身份生成新增/缺失/变化差异、输出标量索引类型、查询字段白名单、
历史产物降级策略、查询游标签名、防篡改和跨查询复用阻断、归档压缩与摘要校验、容量超限关闭、
回放结果原子发布，以及输出热数据归档与完整恢复。
