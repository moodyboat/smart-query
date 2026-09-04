# 智能问数 (Smart Query)

智能数据分析平台，支持自然语言查询、数据挖掘建模、可视化、Word 报告生成、场景化对话。

**GitHub 仓库**: https://github.com/moodyboat/smart-query

## 技术栈

- **Backend**: Spring Boot 3.4.1 + Java 17 + MyBatis-Plus + JWT 鉴权（无 Redis/Flyway：Redis starter 已移除未用、项目未用 Flyway，schema 靠 `db-export` seed dump + 运行时 `DataSeeder`）
- **Frontend**: Vue 3 + Vite 8 + Element Plus + ECharts + Pinia
- **Database**: 达梦 DM8（系统库 smart_query + 示例库 smart_query_sample，COMPATIBLE_MODE=4）；业务库任意（MySQL/PostgreSQL/DM/GBase 通过动态数据源）
- **Python**: `execution-mode` 可配（默认 `process` 系统进程；`docker` 模式 `${smart-query.python.docker-image}` 可配）。
- **Node**: ECharts SSR 图表渲染（`backend/tools/echarts-ssr`）
- **LLM**: GLM-5.1 (默认), GPT-4o, DeepSeek, Ollama

## 快速启动

```bash
./start.sh    # 启动前后端（后端 :9000，前端 :5173）
./stop.sh     # 停止前后端
```

或手动启动:
```bash
cd backend && mvn spring-boot:run   # 端口 9000
cd frontend && npm run dev           # 端口 5173
```

## 项目结构

```
backend/src/main/java/com/smartquery/
├── controller/     # REST API (Chat, Conversation, DataSource, MiningModel, MiningPipeline,
│                   #   Algorithm, Chart, Report, WordReport, Scenario, QueryHistory,
│                   #   PromptTemplate, MetadataConfig, Ontology, Dict, Summary, Dashboard,
│                   #   LlmConfig, Python, ToolTest)
├── engine/         # ReAct 推理引擎 (QueryEngine, ReActEngine, ContextCompactor)
├── service/        # 业务逻辑 (MiningService, PipelineService, ModelScheduleService,
│                   #   WordReportService, ReportSummaryService, ChartImageService,
│                   #   EChartsSsrRenderer, SvgToPngConverter, ScenarioService,
│                   #   RoleScenarioService, ScenarioAuthService,  # 场景 + 按角色授权
│                   #   QueryHistoryService, PromptTemplateService, MetadataConfigService,
│                   #   OntologyService, ConversationSummaryService)
├── entity/         # 数据实体 (MiningModel, MiningPipeline, Algorithm, Chart, Report, etc.)
├── python/         # Python 执行器 (进程沙箱, 熔断器)
├── llm/            # LLM 多模型适配
├── tool/           # ReAct 工具 (execute_sql, execute_python, generate_chart,
│                   #   generate_report, mining_model 等 + AutoRepairHook)
├── prompt/         # 提示词构建 (SystemPrompt, SchemaContext, QueryContext, ScenarioPrompt)
├── datasource/     # 动态数据源管理
├── dict/           # 数据字典
├── sql/            # SQL 处理
├── store/          # 存储抽象
├── dto/            # 数据传输对象
├── logging/        # 对话日志、查询追踪
├── mapper/         # MyBatis Mapper 接口
├── config/         # Spring 配置 (CoordinatorConfig 等)
├── common/         # 公共组件 (ModelStatus, RateLimiter)
└── util/           # 工具类

backend/tools/echarts-ssr/   # Node ECharts SSR 渲染脚本 (render.mjs)

frontend/src/
├── components/
│   ├── ChatPanel.vue          # 对话主界面
│   ├── MiningManager.vue      # 数据挖掘管理 (模型CRUD、训练、发布、调度)
│   ├── PipelineEditor.vue     # 流程编排编辑器 (拖拽节点)
│   ├── Sidebar.vue            # 左侧导航 (会话列表 + 功能入口)
│   ├── EChartsRenderer.vue    # 图表渲染
│   ├── DashboardRenderer.vue  # 仪表盘
│   ├── PromptManager.vue      # 提示词/场景管理
│   ├── QueryHistoryPanel.vue  # 查询历史
│   ├── ScenarioModule.vue     # 场景化对话
│   ├── AdminStatsPanel.vue    # 管理统计
│   ├── mining/                # 挖掘子组件
│   ├── pipeline/              # 流程编排子组件
│   └── ontology/              # 本体子组件
├── stores/                    # Pinia 状态管理 (conversation, mining, ui)
├── api/index.js               # API 层
└── constants.js               # 常量定义
```

## 关键配置 (application.yml)

- 后端端口: `9000`，前端: `5173`
- LLM 默认模型: `glm-5.1`，API Key 通过 `GLM_API_KEY` 环境变量覆盖
- MySQL 密码（业务库示例容器）通过 `MYSQL_PASSWORD` 环境变量覆盖，默认 `900110`；DM8 系统库密码通过 `DM_PASSWORD` 覆盖，默认 `Dameng123`
- Python 执行模式: `process`（默认）/ `docker`（`smart-query.python.execution-mode`；注意 docker 模式有死配置，见「已知问题」）
- ECharts SSR: `smart-query.echarts.*`（node-command / script-path / 超时 / 尺寸）
- ReAct 引擎最大轮次: 15
- SQL 安全模式: 仅允许 SELECT/SHOW/DESCRIBE/EXPLAIN

## API 结构

- 基路径: `/api/v1`
- 对话: GET/POST `/api/v1/chat?conversationId=X&scenario=CODE` (SSE)
- 挖掘模型: CRUD `/api/v1/mining/model`；Pipeline: `/api/v1/mining/pipeline`
- 数据源: CRUD `/api/v1/datasource`；算法: GET `/api/v1/mining/algorithms`
- Word 报告: POST `/api/v1/word-report/conversation/{id}`
- 场景（**复数**，按当前用户角色过滤）: GET `/api/v1/scenarios`、GET `/api/v1/scenarios/admin/all`（admin 全量含禁用）、GET/PUT `/api/v1/scenarios/{id}/roles`（admin 查/改角色授权）、GET `/api/v1/scenarios/code/{code}/prompt`（按权限校验）
- 查询历史/提示词/元数据: `/api/v1/query-history`、`/api/v1/prompt-templates`、`/api/v1/metadata-config`
- 鉴权: POST `/api/v1/auth/login`、GET `/api/v1/auth/me`、POST `/api/v1/auth/logout`；用户管理 `/api/v1/users`（admin）

## Word 报告图表渲染（ECharts SSR）

Word 报告中的图表使用 **ECharts 服务端渲染**，无浏览器、无截图：

1. `EChartsSsrRenderer` 通过 Node 进程（`tools/echarts-ssr/render.mjs`）以 ECharts SSR SVG 模式渲染真实图表
2. `SvgToPngConverter`（Apache Batik）将 SVG 转为 PNG
3. `WordReportService`（Apache POI）将 PNG 嵌入 Word

数据库**仅存 `echartsOption` 配置**，图片在导出时临时生成、不入库。

> 部署需 `node` 可用，并执行 `cd backend/tools/echarts-ssr && npm install`。

## 数据库迁移

项目**未用 Flyway**（早期版本有，现已移除）。schema 靠：
1. `backend/db-export/smart_query_seed.sql` dump 文件（DM8 兼容）—— 部署时由 DBA 用 `disql` 导入 DM8 的 `SMART_QUERY` schema
2. `DataSeeder.java` 兜底：`CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN`（容错"已存在"），保证表/列一定存在（兼容老库与全新库）
3. `scripts/migrate_add_scenario_ui_config.sql` 等增量迁移脚本，DBA 按版本号手动执行

老 `V1-V16` 迁移文件保留在 `backend/src/main/resources/db/migration/` 仅作历史归档，**运行时不加载**。

## 核心业务流程

1. **问数查询**: 用户自然语言 → ReAct 引擎 → SQL/Python 执行 → 结果/图表/报告
2. **问数建模型**: 对话中触发 `mining_model` 工具 → 数据探索 → 特征工程 → 训练 → 验证 → 固化到挖掘模块
3. **挖掘模块**: 拖拽编排 Pipeline → 配置节点参数 → 执行 → 发布 → 定时调度
4. **模型调度**: 已发布模型可配置 cron 定时调度 → 自动训练/预测 → 输出写入数据库
5. **Word 报告**: 对话/报告 → LLM 总结章节 → ECharts SSR 渲染图表 → POI 组装 .docx 下载
6. **场景化配置（2026-06-21）**: 按角色授权场景 + UI 配置下沉到 DB
   - **数据模型**: `sq_scenario.ui_config` JSON 存主题色/欢迎语/能力卡片/示例；`sq_role_scenario(role, scenario_id)` 关联表授权
   - **权限隔离**: `ScenarioAuthService.canAccess(role, code)` —— admin 直通，其他角色查 `sq_role_scenario`
   - **API 守卫**: `ScenarioController.list()` 按当前用户角色过滤；`/scenarios/admin/all`、`POST/PUT/DELETE /scenarios`、`/{id}/roles` 仅 admin
   - **Chat 防绕过**: `ChatController:90-105` 在 SSE 流开前校验 `scenario` 必须在用户授权范围，未授权立即返回 `Error: 无权使用该场景`
   - **提示词生效条件**: `getDefaultPrompt` 五条件全满足（场景存在 + 启用 + scenarioId + isDefault + type=system + 提示词启用）才用场景化提示词，否则回退默认 SystemPromptBuilder
   - **前端**: `ScenarioManager.vue` 管理场景 + UI 配置 + 角色授权；`config/scenarios.js` 删除硬编码，从 `/scenarios` 拉取响应式缓存；登出时 `resetScenarioCache()` 避免账号串扰

## 开发注意事项

- 前端 Vite 开发服务器代理 `/api` → `localhost:9000`
- Python 工作目录: `smart-query.python.workspace-base` 可配（当前默认 `C:/temp/smartquery-workspace`，部署需改 Linux 路径）
- 模型文件保存为 `.pkl` 格式
- 所有 API 响应使用统一信封格式: `{ success, data, message }`
- 实体使用 MyBatis-Plus 逻辑删除 (`deleted` 字段)
- 文档见 `docs/`（`guides/` 使用指南，`archive/` 历史归档，根 `README.md` 入口）

## 前端 UI Design Token 规范（2026-06-21 重构落地）

**所有颜色/字号/字体族/间距/圆角/动画必须用 `src/style.css` 的 CSS 变量**，禁止硬编码。

### 必用 token（禁止 hex 字面量）

| 类别 | token | 用途 |
|---|---|---|
| 品牌色 | `--brand-primary` `--brand-primary-hover` `--brand-primary-active` `--brand-primary-light` `--brand-primary-lighter` `--brand-gradient` | 主色 #2563eb（Tailwind blue-600）+ 渐变 |
| 文本 | `--text-primary` `--text-regular` `--text-secondary` `--text-muted` | 4 级文本色阶 |
| 背景/边框 | `--bg` `--surface` `--border` `--border-light` `--hover` | 页面/卡片/分隔 |
| 状态 | `--color-success` `--color-warning` `--color-danger` `--color-info` `--color-pink` `--color-python`（+ `-light`）| 业务状态 + Python 官方蓝 |
| 间距 | `--space-xs/sm/md/lg/xl/2xl` | 4/8/12/16/20/24px |
| 圆角 | `--radius-sm/md/lg/xl/pill` | 4/6/8/10/999px |
| 字号 | `--font-xs/sm/md/base/lg/xl/2xl` | 11-18px |
| 字体族 | `--font-family-sans` `--font-family-mono` | 代码块/SQL 必须用 mono |
| 动画 | `--transition-fast/base/slow` | 0.15/0.2/0.3s |
| 层级 | `--z-dropdown/sticky/modal/drawer/toast/tooltip` | 1000-1500 |
| 阴影 | `--shadow-sm/md/lg` | 卡片阴影 |
| 代码高亮 | `--syntax-bg/fg/keyword/string/number/comment/function/type/variable/constant/header-bg/line-number` | VS Code Dark+ 独立色板，与品牌色物理隔离 |

### 公共类（管理页直接用）

```html
<div class="page-container">              <!-- max-width 1400px + margin auto + padding -->
  <div class="page-header">               <!-- flex + gap -->
    <button class="back-btn">返回</button> <!-- 品牌色文字 + hover light bg -->
    <h2 class="page-title">标题</h2>       <!-- 18px / 600 / text-primary -->
  </div>
  ...
</div>
```

已应用：ScenarioManager / PromptManager / MiningManager / DataSourceManager。

### 例外（可保留硬编码）

- VS Code Dark+ 代码高亮色（已抽 `--syntax-*`，**不要混入品牌色**）
- ECharts 图表数据色（独立色板，按图表语义定）
- 组件特色配色（如 ScenarioModule 紫色渐变作 AI 模块标识）
- placeholder 占位提示（用户输入示例，非真色值）
- SQL 语法高亮 regex 字符串（JS 拼接，无法用 CSS var）

### element-plus 主题

`--el-color-primary` 已绑定到 `--brand-primary`，所有 element-plus 控件（el-button/el-input/el-select 等）自动跟随品牌色，**禁止在组件里用 `:deep(.el-button)` 覆盖颜色**。

### 向后兼容

`--primary` 是 `--brand-primary` 的别名（已废弃但不删，避免破坏老代码）；新代码用 `--brand-primary`。

## 已知问题（2026-06-21 代码核查，均有 file:line 证据）

- **[已修复·2026-09-04] GLM API Key 默认值泄露**：`application.yml` 已改为仅从 `GLM_API_KEY` 环境变量读取；历史中暴露过的密钥仍需立即在智谱控制台轮换，并评估从 Git 历史清除。
- **[已修复·2026-06-19] 零鉴权**：已建成 JWT 鉴权体系（`config/JwtUtil` HS256 + `config/AuthInterceptor` 拦截 `/api/**` + `entity/User`/`sq_user` 表 + `controller/AuthController` login/me/logout + `controller/UserController` 用户管理）。CORS 仍开放（`WebConfig`）。详见 `docs/DEPENDENCY_AUDIT.md`。
- **[安全] 凭据明文**：DM8 系统库密码默认 `Dameng123`（`application.yml:13` `${SPRING_DATASOURCE_PASSWORD:Dameng123}`）；MySQL 业务库示例密码默认 `900110`（仅 docker-compose 的 mysql 容器用，非系统库）；动态数据源密码在 DB 表明文存储、读取时直传 Hikari（`DataSourceManager:161-180`）。
- **[已修复·2026-06-19] Python docker 镜像/超时死配置**：`PythonExecutor:62,68` 现读 `smart-query.python.execution-mode` / `smart-query.python.docker-image`（与 yml 一致，可配）；`PythonExecuteTool:23` 超时 key 已由 `python-tool.*` 改为 `smart-query.python.default-timeout-ms`（与 yml 对齐，配置可达）。
- **[已彻底清理·2026-06-21] 协调器死代码**：原 `CoordinatorIntegration`（needsCoordination 恒 false）+ `engine/Coordinator`/`AgentTask`/`AgentTaskExecutor`（MiningService 注入但无任何方法调用）+ `coordinator/` 整个包（仅被 CoordinatorIntegration 引用）+ `test/coordinator/TaskCoordinatorTest` 全部删除（约 1340 行）。真实多算法对比走 `MiningModelTool.compare` action（需 `source_table`+`target_column`，调 `MiningService.trainModel`）。
- **[已彻底清理·2026-06-21] 算法注册孤儿**：`mining/AlgorithmRegistry`（137 行，硬编码 9 种算法）全无外部引用——`AlgorithmService` 自己定义 ALIAS_MAP 从 DB `AlgorithmMapper` 取数据，根本不依赖 Registry。已删除。
- **[部署] 容器化已落地，CI 已补编译/构建**：已有 `backend/Dockerfile`（Node20+JRE17）、`frontend/Dockerfile`（nginx）、`docker-compose.yml`（**DM8 系统库走 host.docker.internal:5236** + mysql 业务库示例容器 + redis + backend + frontend + python 执行镜像）、`scripts/`（build/airpack）、`docs/guides/DEPLOYMENT.md`、`.env.example`、`.github/workflows/build.yml`（编译+构建，无测试）。开发模式 `start.sh` = `mvn spring-boot:run` + `npm run dev`。详见 `docs/DEPENDENCY_AUDIT.md`。
- **[已确认·2026-06-21] ReAct 最大轮次**：`application.yml` `react.max-turns: 15` 生效（`ReActEngine.java:48` `@Value("${react.max-turns:20}")` 默认 20 被 yml 覆盖为 15）。CLAUDE.md 旧值 15 正确。

## 业务场景（2026-07-19 新增）：财司指标分析 `metric_analysis`

为 `指标体系梳理` 文件夹（位于本目录下）建好的元数据 + 结果表 + 字典表专门定制的问数场景，演示"基于业务元数据驱动的智能问数"模式。

### 已创建对象

| 类型 | ID | 名称 / code | 说明 |
|---|---|---|---|
| DataSource | 8 | 财司指标库(MySQL) | `host.docker.internal:3306/dws`，root/900110 |
| Scenario | 8 | 财司指标分析 (`metric_analysis`) | 锁定数据源 8，限定 3 张表 |
| PromptTemplate | 7 | metric_analysis_system | type=system, isDefault=true |

### 限定的 3 张表（`sq_scenario.allowed_tables`）

- `dws.dws_metric_info` — 指标基本信息表，117 行（54 基础 + 15 一级复合 + 48 二级复合）
- `dws.dws_metric_val` — 指标结果表，约 102 万行（2020-06 ~ 2026-07）
- `dim.dw_code_dict` — 数据标准代码字典，25,398 行（390 个字段）

### 关键字段说明（已写入 prompt）

- `metric_code` 编码格式：`业务线-主题-序号-类型`（B=基础，C=复合）
- `schd_lvl` 调度层级：0=基础，1=日均类一级复合，2=同环比类二级复合
- `metric_exp` 公式格式：基础 `sum(FIELD 中文)`，复合中文可读（如 `(本期基础 - 去年同期基础) / 去年同期基础`）
- `dim` 字段为 JSON，需用 `JSON_EXTRACT(dim, '$.KEY')` 解析

### 部署注意

`sq_scenario` 表原有的列缺 4 个新字段（`data_source_id`、`schema_name`、`allowed_tables`、`prompt_override`）。已通过 `ALTER TABLE` 补齐，但镜像里 MyBatis 实体旧版 jar 不会持久化这些字段，**重建 backend 镜像后才能完整写入**。

### 已知问题

- **GLM API key 余额不足**：当前 `.env` 中配置的旧密钥已耗尽，所有 LLM 调用返回 `1113 余额不足`。需要充值或更换密钥才能跑通对话。
