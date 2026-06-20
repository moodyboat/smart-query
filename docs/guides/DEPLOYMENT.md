# 部署指南（Deployment）

> 状态（2026-06-20）：✅ **达梦 DM8 系统库适配完成 + 本地全链路验证通过** —— 系统库 `smart_query` 现跑在 DM8（`COMPATIBLE_MODE=4` + `CASE_SENSITIVE=0`），后端 Hikari 用 SYSDBA 连接 + `SET SCHEMA SMART_QUERY` 切 schema，14 个核心 API 全部 200（登录/JWT/数据源/算法/挖掘模型/Pipeline/会话/用户），写路径（会话/用户/数据源）disql 直查可见。MySQL 仍保留为业务库示例容器。镜像：`smart-query-backend`/`-frontend`/`-python`；离线打包 `scripts/airpack.sh`/`airload.sh`。**下一阶段：业务库 DM 兼容**（`DictController`/`SchemaExploreTool` 等元数据查询走 `information_schema`，客户业务库若也用 DM 需改 `ALL_TAB_COLUMNS` 路径）。

## 一、架构

```
┌─────────────────────────────────────────────────────────────┐
│  docker compose                                              │
│                                                              │
│  frontend(nginx:80)  ──/api/──▶  backend(:9000, JRE17+Node) │
│   SPA dist + 反代                    │                       │
│                                      ├─▶ DM8:5236 (host)    │
│                                      │   (smart_query 系统库│
│                                      │    schema;dataSeeder │
│                                      │    smart_query_sample │
│                                      │    示例数据)           │
│                                      ├─▶ redis:6379          │
│                                      └─ docker.sock ──docker run──▶ smart-query-python │
│                                                                   (pandas/sklearn/xgboost/ │
│                                                                    lightgbm，数据挖掘执行)   │
└─────────────────────────────────────────────────────────────┘
```

- **backend**：Spring Boot jar（`maven` 多阶段构建）+ JRE17 + Node20（ECharts SSR 调 `tools/echarts-ssr/render.mjs`）。
- **frontend**：`vite build` → nginx 托管 SPA，`/api/` 反代后端（SSE 已关缓冲、超时 300s）。
- **python**：独立镜像，**后端通过宿主 docker socket 以 `docker run` 调用**（进程隔离信任边界=容器；进程内 `PythonSandbox` 降为粗筛）。非常驻服务，仅按需拉起。
- **mysql**：首次启动执行 `docker/db-init.sh`，建 `smart_query`（后端 Flyway 管理）+ `smart_query_sample`（导入示例数据）。
- **redis**：会话/缓存。

## 二、前置

- Docker + Docker Compose（本机已验证 Docker 29.4.3 / Compose v5.1.4）。
- GLM API Key（问数需要）。

## 三、配置

复制 `.env`（或直接导出环境变量）：

```bash
export GLM_API_KEY=你的key
export MYSQL_PASSWORD=900110   # 可改
```

关键可覆盖项（compose env → application.yml）：
| env | 作用 | 默认 |
|---|---|---|
| `GLM_API_KEY` | LLM 密钥 | yml 内置（私有化可保留） |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_PASSWORD` | 系统库连接 | mysql / 3306 / 900110 |
| `REDIS_HOST` / `REDIS_PORT` | Redis | redis / 6379 |
| `SMART_QUERY_PYTHON_EXECUTION_MODE` | `docker` / `process` | docker |
| `SMART_QUERY_PYTHON_DOCKER_IMAGE` | python 执行镜像 | smart-query-python:latest |

## 四、构建与启动

```bash
# 一键：mvn 打 jar（本地热 ~/.m2，快）→ 拷 app.jar → 构建全部 docker 镜像
./scripts/build.sh

# 启动全部（mysql 健康后 backend 起，Flyway 自动迁移 smart_query）
docker compose up -d
```

> 后端镜像采用「拷预构建 jar」模式（运行时态 = Node20 + JRE17），避开 in-Docker 冷 maven 下载。`scripts/build.sh` 自动完成 `mvn -DskipTests package` + `cp target/*.jar backend/app.jar` + `docker compose build`。CI/离线打包用 `scripts/airpack.sh`（同样的 mvn 步骤）。

> **宿主端口**默认避开本地开发端口（3307/9001/5174），可与本地 `start.sh` 同时跑；专用部署机可在 `.env` 设 `MYSQL_HOST_PORT=3306 BACKEND_HOST_PORT=9000 FRONTEND_HOST_PORT=80`。

- 前端：http://localhost:${FRONTEND_HOST_PORT:-5174}
- 后端 API：http://localhost:${BACKEND_HOST_PORT:-9001}/api/v1/...
- 日志：`docker compose logs -f backend`
- 停止：`docker compose down`（加 `-v` 清库数据）

## 四·二、离线部署（甲方 airgap，无网络）

甲方测试环境无网络，所有镜像必须在联网环境打好包、拷到现场导入。

```bash
# 联网环境：构建并导出全部镜像到 dist/smart-query-images.tar
./scripts/airpack.sh

# 把整个项目目录（含 dist/、docker-compose.yml、docker/ 等）拷到甲方现场

# 甲方现场（无网络）：导入镜像
./scripts/airload.sh

# 启动（镜像已全部就位，不联网）
docker compose up -d
```

`airpack.sh` 导出的镜像：`smart-query-backend` / `smart-query-frontend` / `smart-query-python` + `mysql:8.0` / `redis:7-alpine`（**不需要** nginx/node/python 等 base——已烘焙进项目镜像，甲方 `docker compose up` 不重建）。save/load 机制本地已实测通过。注意：**达梦适配完成后，mysql 镜像替换为达梦驱动 vendor 方案**（见路线图）。

## 五、本地测试要点

- backend 启动日志应见 Flyway 迁移到 V16、`smart_query` 建表完成。
- 在 UI 添加数据源指向 `smart_query_sample`（容器内 mysql，驱动 MySQL）即可问数示例数据。
- Python 挖掘：execution-mode=docker 时后端 `docker run smart-query-python`；**注意 DooD 路径共享**（后端容器写脚本到 `/tmp/smartquery-artifacts`，python 容器需能读到同一宿主路径——当前用命名卷 `sq_artifacts` 挂载，若临时脚本写到系统 `/tmp` 需进一步对齐，见已知限制）。

## 六、已知限制 / 待办（下一轮迭代）

1. ✅ **DooD Python 执行已打通**（容器内挖掘隔离 + DB 访问，本地验证通过）：
   - 后端镜像内置 docker CLI（`COPY --from=docker:25-cli`）；后端容器挂宿主 `/var/run/docker.sock`。
   - `PythonExecutor.buildDockerProcess`：按**卷名**挂 `sq_artifacts`/`sq_workspace` 共享卷（`-v sq_artifacts:/tmp/smartquery-artifacts`）+ `-e` 转发 `_SMARTQUERY_DB_URL`/`PYTHONIOENCODING`（docker run 不继承父进程 env）+ `--network smartquery-net`（让 python 容器解析 `mysql`）。
   - 临时脚本落 `artifact-dir`（共享卷内），python 容器同路径可见。
   - compose 配 `SMART_QUERY_PYTHON_DOCKER_{IMAGE,SHARED_VOLUME,WORKSPACE_VOLUME,NETWORK}` env + 卷/网络用显式 `name:`（去项目前缀）。
   - 实测：`/api/v1/python/execute` 跑 pandas 读 `smart_query_sample.customer` 返回 100 行，exitCode 0。
2. **`PythonExecutor:375` 已改为读 `docker-image` 配置**（原写死 `python:3.11-slim`），死配置已消除。
3. **离线/airgap 打包**：甲方无网络，已提供 `scripts/airpack.sh`（联网环境导出镜像 tar）+ `scripts/airload.sh`（现场导入），见「四·二 离线部署」。
4. **GLM key**：默认值在 `application.yml`（私有化保留即可，用户确认非问题）。

## 六·二、故障排查

| 现象 | 排查 |
|---|---|
| `failed to connect to the docker API ... no such file or directory` | Docker Desktop 没启动。`open -a Docker`，等 `docker ps` 能跑再构建。 |
| python/backend 镜像构建很久 | 首次构建要拉基础镜像 + pip 装科学计算包（pandas/sklearn/xgboost），5-10 分钟正常。看 `docker system df` 的 Build Cache 是否在涨判断是否推进。 |
| `npm ci` 失败 | frontend / echarts-ssr 都有 `package-lock.json`，正常不会失败；若失败检查 lock 文件是否与 package.json 同步。 |
| backend 启动报数据库连不上 | 确认 `MYSQL_HOST=mysql`（compose 服务名）、mysql 健康（`docker compose ps`）、`MYSQL_PASSWORD` 与 mysql 服务一致。 |
| Flyway 报错 | `smart_query` 库由 Flyway 管理；确认库为空启动（不要同时导 `smart_query_seed.sql`，否则与 Flyway 建表冲突）。 |
| 端口占用 9000/5173/3306 | `docker compose down` 后改 compose 的端口映射。 |
| 前端能开但问数 500 | 看后端日志 `docker compose logs backend`；多半是 `GLM_API_KEY` 未设或 LLM 不通。 |

## 七、已完成：达梦 DM8 系统库适配（2026-06-20）

甲方测试环境：**达梦 DM8（不能用 MySQL）、无网络、要 Redis、要数据挖掘、Docker 可用、DM8 可开 MySQL 兼容模式（`COMPATIBLE_MODE=4`）**。

完成项（带验证证据）：
1. ✅ **JDBC 驱动**：`backend/lib/DmJdbcDriver8.jar` + pom `<systemScope>` + `includeSystemScope=true`，fat jar 内 `BOOT-INF/lib/DmJdbcDriver-8.jar` 已就位。
2. ✅ **数据源切换**：`application.yml` 主数据源全 `${}` 参数化（URL/USER/PASSWORD/DRIVER/INIT_SQL），默认 `jdbc:dm://host:5236` + `SET SCHEMA SMART_QUERY`；docker-compose backend 环境变量同步切 DM。
3. ✅ **DDL 跨库化**：`DataSeeder` 去 `ON UPDATE CURRENT_TIMESTAMP`/`UNIQUE KEY`/`ENGINE`/`CHARSET`；`DatabaseFixConfig` 抛弃 `INFORMATION_SCHEMA` 改 try ALTER + 容错 `-2116 already exists`。
4. ✅ **保留字兼容**：DM 把 `MODEL` 当保留字（DM 支持 MODEL clause），`ChatMessage.model` 字段加 `@TableField("\"MODEL\"")`，`ChatMessageMapper` SQL 同步 `"MODEL" AS model`。修后 `chat/history` 从 500 → 返回 26 条消息。
5. ✅ **Python 挖掘**：`DbUrlUtil.buildSqlalchemyUrl` 加 `dm` 分支（`dm+dmPython://`），脱敏正则同步；`docker/python/requirements.txt` 标注 dmPython 按需启用。
6. ⏸️ **未做（按需）**：业务库元数据查询 `information_schema` 兼容（`DictController`/`DataSourceController`/`MetadataConfigController`/`SchemaExploreTool`/`MiningPredictionService`/`PipelineService`），客户业务库若也用 DM 才需要。

> DM8 实例独立容器（`smartquery-dm8`），不在 docker-compose 内；backend 通过 `host.docker.internal:5236` 连。schema 由用户预先建好（22 张表 + 数据已迁）。
> 详见 commit `2ca8f3b`（迁移）+ `294a54d`（model 保留字修复）。
