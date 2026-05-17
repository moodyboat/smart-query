# 智能问数 (Smart Query)

智能数据分析平台，支持自然语言查询、数据挖掘建模、可视化、报告生成。

**GitHub 仓库**: https://github.com/moodyboat/smart-query

## 技术栈

- **Backend**: Spring Boot 3.4.1 + Java 17 + MyBatis-Plus + Redis + Flyway
- **Frontend**: Vue 3 + Vite 8 + Element Plus + ECharts + Pinia
- **Database**: MySQL 8.0 (smart_query 系统库 + smart_query_sample 示例库)
- **Python**: 系统 python3 进程模式执行（非 Docker）
- **LLM**: GLM-5.1 (默认), GPT-4o, DeepSeek, Ollama

## 快速启动

```bash
./start.sh    # 启动前后端
./stop.sh     # 停止前后端
```

或手动启动:
```bash
cd backend && mvn spring-boot:run   # 端口 8080
cd frontend && npm run dev           # 端口 5173
```

## 项目结构

```
backend/src/main/java/com/smartquery/
├── controller/     # REST API (Chat, MiningModel, MiningPipeline, DataSource, etc.)
├── engine/         # ReAct 推理引擎 (QueryEngine, Coordinator, AgentTask)
├── service/        # 业务逻辑 (MiningService, PipelineService, ModelScheduleService)
├── entity/         # 数据实体 (MiningModel, MiningPipeline, Algorithm, etc.)
├── mining/         # 挖掘模块 (算法注册, 流程编排)
├── python/         # Python 执行器 (进程沙箱)
├── llm/            # LLM 多模型适配
├── tool/           # ReAct 工具 (execute_sql, execute_python, generate_chart, mining_model, etc.)
├── prompt/         # 提示词构建 (SystemPrompt, SchemaContext, QueryContext)
├── datasource/     # 动态数据源管理
├── logging/        # 对话日志、查询追踪
├── mapper/         # MyBatis Mapper 接口
├── config/         # Spring 配置
├── common/         # 公共组件 (ModelStatus, RateLimiter)
└── util/           # 工具类

frontend/src/
├── components/
│   ├── ChatPanel.vue       # 对话主界面
│   ├── MiningManager.vue   # 数据挖掘管理 (模型CRUD、训练、发布、调度)
│   ├── PipelineEditor.vue  # 流程编排编辑器 (拖拽节点)
│   ├── Sidebar.vue         # 左侧导航 (会话列表)
│   ├── EChartsRenderer.vue # 图表渲染
│   ├── DashboardRenderer.vue # 仪表盘
│   └── ...
├── stores/                 # Pinia 状态管理
│   ├── conversation.js     # 对话状态
│   ├── mining.js           # 挖掘模块状态
│   └── ui.js               # UI 状态
├── api/index.js            # API 层
└── constants.js            # 常量定义
```

## 关键配置 (application.yml)

- LLM 默认模型: `glm-5.1`，API Key 通过 `GLM_API_KEY` 环境变量覆盖
- MySQL 密码通过 `MYSQL_PASSWORD` 环境变量覆盖，默认 `900110`
- Python 执行模式: `process`（系统进程）
- ReAct 引擎最大轮次: 15
- SQL 安全模式: 仅允许 SELECT/SHOW/DESCRIBE/EXPLAIN

## API 结构

- 基路径: `/api/v1`
- 对话: POST `/api/v1/chat/{conversationId}` (SSE)
- 挖掘模型: CRUD `/api/v1/mining/models`
- Pipeline: CRUD `/api/v1/mining/pipelines`
- 数据源: CRUD `/api/v1/datasources`
- 算法: GET `/api/v1/algorithms`

## 数据库迁移

Flyway 管理数据库版本，迁移文件在 `backend/src/main/resources/db/migration/`。
当前 schema 版本: V10。

## 核心业务流程

1. **问数查询**: 用户自然语言 → ReAct 引擎 → SQL/Python 执行 → 结果/图表/报告
2. **问数建模型**: 对话中触发 `mining_model` 工具 → 数据探索 → 特征工程 → 训练 → 验证 → 固化到挖掘模块
3. **挖掘模块**: 拖拽编排 Pipeline → 配置节点参数 → 执行 → 发布 → 定时调度
4. **模型调度**: 已发布模型可配置 cron 定时调度 → 自动训练/预测 → 输出写入数据库

## 开发注意事项

- 前端 Vite 开发服务器代理 `/api` → `localhost:8080`
- Python 工作目录: `/tmp/smartquery-workspace/`
- 模型文件保存为 `.pkl` 格式
- 所有 API 响应使用统一信封格式: `{ success, data, message }`
- 实体使用 MyBatis-Plus 逻辑删除 (`deleted` 字段)
