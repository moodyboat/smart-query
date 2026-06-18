# 智能问数 (Smart Query)

智能数据分析平台：自然语言查询、数据挖掘建模、可视化、Word 报告生成、场景化对话。

## ✨ 核心能力

- **自然语言问数**：自然语言 → ReAct 引擎 → SQL/Python 执行 → 结果/图表/报告
- **数据挖掘**：拖拽式 Pipeline 编排，模型训练 / 验证 / 发布 / cron 调度
- **可视化**：ECharts 图表、仪表盘
- **Word 报告**：LLM 总结 + ECharts SSR 真实图表渲染，导出 `.docx`
- **场景化对话**：6 大专业场景主题

## 🚀 快速启动

```bash
./start.sh    # 后端 :9000 + 前端 :5173
./stop.sh
```

打开 http://localhost:5173

**前置依赖**：JDK 17+、Node 18+、MySQL 8.0、（可选）Python 3、Redis。

**环境变量**：
- `GLM_API_KEY`：LLM API Key（默认模型 GLM-5.1）
- `MYSQL_PASSWORD`：MySQL 密码（默认 `900110`）

## 🛠 技术栈

- **后端**：Spring Boot 3.4.1 · Java 17 · MyBatis-Plus · Redis · Flyway · Apache POI · Batik
- **前端**：Vue 3 · Vite · Element Plus · ECharts · Pinia
- **数据库**：MySQL 8.0（`smart_query` 系统库 + `smart_query_sample` 示例库）
- **图表渲染**：Node ECharts SSR（Word 报告图表，无浏览器、无截图）
- **LLM**：GLM-5.1 / GPT-4o / DeepSeek / Ollama

## 📁 项目结构

```
├── backend/        Spring Boot 后端（API、ReAct 引擎、挖掘、Word 报告、ECharts SSR）
├── frontend/       Vue 3 前端
├── docs/           文档（guides/ 使用指南，archive/ 历史归档）
├── tools/          Maven + ECharts SSR 渲染脚本
├── start.sh / stop.sh
├── CLAUDE.md       项目说明（供 Claude Code 读取）
└── README.md
```

详细结构与开发说明见 [CLAUDE.md](CLAUDE.md)。

## 📖 文档

- [文档索引](docs/README.md)
- [使用指南](docs/guides/) — 场景对话、Word 报告、界面体验等
- [历史归档](docs/archive/) — 修复报告、验证记录、调试截图

## 🗄 数据库

Flyway 管理迁移（`backend/src/main/resources/db/migration/`），当前 schema 版本 **V16**。

## 🔗 相关

- GitHub：https://github.com/moodyboat/smart-query
