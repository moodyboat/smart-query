# 智能问数 (Smart Query)

智能数据分析平台，支持自然语言查询、数据挖掘建模、可视化、Word 报告生成、场景化对话。

**GitHub 仓库**: https://github.com/moodyboat/smart-query

## 技术栈

- **Backend**: Spring Boot 3.4.1 + Java 17 + MyBatis-Plus + Redis + Flyway
- **Frontend**: Vue 3 + Vite 8 + Element Plus + ECharts + Pinia
- **Database**: MySQL 8.0 (smart_query 系统库 + smart_query_sample 示例库)
- **Python**: 系统 python3 进程模式执行（非 Docker）
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
├── coordinator/    # 任务协调器（并行任务编排）
├── agent/          # Agent 任务抽象
├── service/        # 业务逻辑 (MiningService, PipelineService, ModelScheduleService,
│                   #   WordReportService, ReportSummaryService, ChartImageService,
│                   #   EChartsSsrRenderer, SvgToPngConverter, ScenarioService,
│                   #   QueryHistoryService, PromptTemplateService, MetadataConfigService,
│                   #   OntologyService, ConversationSummaryService)
├── entity/         # 数据实体 (MiningModel, MiningPipeline, Algorithm, Chart, Report, etc.)
├── mining/         # 挖掘模块 (算法注册, 流程编排)
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
- MySQL 密码通过 `MYSQL_PASSWORD` 环境变量覆盖，默认 `900110`
- Python 执行模式: `process`（系统进程）
- ECharts SSR: `smart-query.echarts.*`（node-command / script-path / 超时 / 尺寸）
- ReAct 引擎最大轮次: 15
- SQL 安全模式: 仅允许 SELECT/SHOW/DESCRIBE/EXPLAIN

## API 结构

- 基路径: `/api/v1`
- 对话: POST `/api/v1/chat/{conversationId}` (SSE)
- 挖掘模型: CRUD `/api/v1/mining/models`；Pipeline: `/api/v1/mining/pipelines`
- 数据源: CRUD `/api/v1/datasources`；算法: GET `/api/v1/algorithms`
- Word 报告: GET `/api/v1/word-report/download/conversation/{id}`
- 场景/查询历史/提示词/元数据: `/api/v1/scenarios`、`/api/v1/query-history`、`/api/v1/prompt-templates`、`/api/v1/metadata-config`

## Word 报告图表渲染（ECharts SSR）

Word 报告中的图表使用 **ECharts 服务端渲染**，无浏览器、无截图：

1. `EChartsSsrRenderer` 通过 Node 进程（`tools/echarts-ssr/render.mjs`）以 ECharts SSR SVG 模式渲染真实图表
2. `SvgToPngConverter`（Apache Batik）将 SVG 转为 PNG
3. `WordReportService`（Apache POI）将 PNG 嵌入 Word

数据库**仅存 `echartsOption` 配置**，图片在导出时临时生成、不入库。

> 部署需 `node` 可用，并执行 `cd backend/tools/echarts-ssr && npm install`。

## 数据库迁移

Flyway 管理数据库版本，迁移文件在 `backend/src/main/resources/db/migration/`。
当前 schema 版本: V16。

## 核心业务流程

1. **问数查询**: 用户自然语言 → ReAct 引擎 → SQL/Python 执行 → 结果/图表/报告
2. **问数建模型**: 对话中触发 `mining_model` 工具 → 数据探索 → 特征工程 → 训练 → 验证 → 固化到挖掘模块
3. **挖掘模块**: 拖拽编排 Pipeline → 配置节点参数 → 执行 → 发布 → 定时调度
4. **模型调度**: 已发布模型可配置 cron 定时调度 → 自动训练/预测 → 输出写入数据库
5. **Word 报告**: 对话/报告 → LLM 总结章节 → ECharts SSR 渲染图表 → POI 组装 .docx 下载

## 开发注意事项

- 前端 Vite 开发服务器代理 `/api` → `localhost:9000`
- Python 工作目录: `/tmp/smartquery-workspace/`
- 模型文件保存为 `.pkl` 格式
- 所有 API 响应使用统一信封格式: `{ success, data, message }`
- 实体使用 MyBatis-Plus 逻辑删除 (`deleted` 字段)
- 文档见 `docs/`（`guides/` 使用指南，`archive/` 历史归档，根 `README.md` 入口）
