# 智能问数 + 指标引擎 集成系统

## 系统架构

本系统采用模块化集成方案，保持了智能问数和指标引擎的独立性，通过前端入口实现功能整合。

### 组件说明

| 组件 | 端口 | 说明 |
|------|------|------|
| 智能问数后端 | 8080 | Spring Boot 应用，数据库：smart_query |
| 指标引擎后端 | 21050 | Spring Boot 应用，数据库：ads |
| 智能问数前端 | 5173 | Vue 3 SPA，主要交互界面 |
| 指标引擎前端 | 5174 | Vue 3 SPA，指标管理界面 |

### 数据库架构

- **smart_query**：智能问数专用数据库，包含对话、消息、数据源、数据挖掘等表
- **ads**：指标引擎专用数据库，包含指标、规则、预警等209个表

## 快速启动

### 一键启动（推荐）

```bash
./start-all.sh
```

这将自动启动所有4个组件（智能问数后端/前端 + 指标引擎后端/前端）。

### 停止所有服务

```bash
./stop-all.sh
```

### 单独启动

如需单独启动某个组件：

```bash
# 智能问数后端
cd backend && mvn spring-boot:run

# 指标引擎后端
cd 指标引擎/backend/gdgp-metric/gdgp-metric-provider && mvn spring-boot:run

# 智能问数前端
cd frontend && npm run dev

# 指标引擎前端
cd 指标引擎/frontend/metric-app && npm run dev
```

## 访问地址

| 系统 | URL | 说明 |
|------|-----|------|
| 智能问数 | http://localhost:5173 | 主要入口，自然语言查询 |
| 指标引擎 | http://localhost:5174 | 或从智能问数侧边栏点击"指标引擎"按钮 |
| 智能问数健康检查 | http://localhost:8080/actuator/health | 后端状态 |
| 指标引擎健康检查 | http://localhost:21050/api/actuator/health | 后端状态 |

## 使用指南

### 1. 智能问数功能

- **自然语言查询**：直接用自然语言提问，系统自动生成SQL并执行
- **数据挖掘**：点击侧边栏"数据挖掘管理"，构建和训练机器学习模型
- **可视化分析**：自动生成图表和仪表盘
- **报告生成**：一键生成分析报告

### 2. 指标引擎功能

在智能问数侧边栏点击"指标引擎"按钮，打开指标引擎界面：

- **指标定义**：管理基础指标和复合指标
- **指标查询**：在指标超市中查询和分析指标
- **规则配置**：设置预警规则和监控
- **脚本管理**：批量执行数据处理脚本

### 3. 系统管理

- **用户管理**：管理系统用户和权限
- **角色管理**：配置角色和菜单权限
- **数据源管理**：配置业务数据源连接

## 技术栈

### 后端

- **智能问数**：Spring Boot 3.4.1 + Java 17 + MyBatis-Plus + Redis
- **指标引擎**：Spring Boot 3.2.5 + Java 17 + MyBatis-Plus + Redis

### 前端

- **框架**：Vue 3 + Vite + Element Plus
- **状态管理**：Pinia
- **图表**：ECharts
- **构建工具**：Vite

## 配置说明

### 环境要求

- Java 17+
- Node.js 18+
- MySQL 8.0+
- Redis

### 数据库配置

默认配置（可修改 application.yml）：

```yaml
# 智能问数 - backend/src/main/resources/application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/smart_query
    username: root
    password: 900110

# 指标引擎 - 指标引擎/backend/.../application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ads
    username: root
    password: 900110
```

### 前端配置

```bash
# 智能问数 - frontend/.env.development
VITE_API_BASE_URL=http://localhost:8080

# 指标引擎 - 指标引擎/frontend/metric-app/.env.development
VITE_API_BASE_URL=http://localhost:21050
```

## 开发说明

### 添加新功能

1. **智能问数**：修改 `backend/src/` 和 `frontend/src/`
2. **指标引擎**：修改 `指标引擎/backend/` 和 `指标引擎/frontend/`
3. **集成功能**：修改 `frontend/src/components/Sidebar.vue`

### 日志文件

所有日志保存在 `logs/` 目录：

- `smart-query-backend.log` - 智能问数后端日志
- `metric-engine-backend.log` - 指标引擎后端日志
- `smart-query-frontend.log` - 智能问数前端日志
- `metric-engine-frontend.log` - 指标引擎前端日志

## 故障排查

### 端口占用

如果启动失败，检查端口是否被占用：

```bash
# 检查端口
lsof -i :8080   # 智能问数后端
lsof -i :21050  # 指标引擎后端
lsof -i :5173   # 智能问数前端
lsof -i :5174   # 指标引擎前端

# 释放端口
kill -9 <PID>
```

### 数据库连接失败

检查 MySQL 服务是否运行，数据库是否已创建：

```sql
-- 创建数据库
CREATE DATABASE IF NOT EXISTS smart_query;
CREATE DATABASE IF NOT EXISTS ads;
```

### 前端无法访问

检查后端是否已启动，API代理配置是否正确。

## 项目结构

```
智能问数/
├── backend/                           # 智能问数后端
├── frontend/                          # 智能问数前端
├── 指标引擎/
│   ├── backend/                       # 指标引擎后端
│   └── frontend/metric-app/           # 指标引擎前端
├── start-all.sh                       # 一键启动脚本
├── stop-all.sh                        # 一键停止脚本
├── start.sh                           # 智能问数启动脚本
├── stop.sh                            # 智能问数停止脚本
└── INTEGRATION_README.md              # 本文档
```

## 更新日志

### 2026-05-29
- 完成指标引擎模块化集成
- 添加智能问数侧边栏指标引擎入口
- 创建完整的启动/停止脚本
- 配置指标引擎前端独立端口（5174）
