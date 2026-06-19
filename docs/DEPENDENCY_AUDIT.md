# 依赖审计报告

> 审计日期：2026-06-19
> 范围：全量代码依赖（后端 Maven / 前端 npm / Python / Node SSR）
> 性质：静态只读分析，所有结论带 file:line 证据。代码未变更则结论不变。

## 扫描范围

| 清单文件 | 生态 | 直接依赖数 |
|---|---|---|
| `backend/pom.xml` | Java / Maven (Spring Boot 3.4.1 + Java 17) | 23 |
| `frontend/package.json` | npm (Vue 3.5 + Vite 8) | 11 |
| `backend/tools/echarts-ssr/package.json` | Node (Word 报告图表 SSR) | 1 |
| `docker/python/requirements.txt` | Python (挖掘训练/预测/特征工程脚本) | 10 |

---

## 一、确证冗余依赖（建议删除，删前再编译/构建验证一次）— ✅ 已修复（2026-06-19）

> **8 项全部删除并验证**：后端 5（webflux/reactor-test/data-redis/cache/caffeine）+ 孤儿 redis 配置块、前端 2（vue-echarts/highlight.js）、Python 1（scipy）。删前用自己的 grep 复核每项 0 引用；删后后端 `mvn compile` 通过、启动 1.69s 无错误，前端 `npm run build` 通过，冒烟测试登录/JWT/受保护接口全绿（带 token 200、无 token 401）。`docker-compose` 的 redis 服务属部署设施、未动（备注：现可选）。

### 后端 Maven

| # | 依赖 | 证据（全仓搜索 0 命中） | 说明 |
|---|---|---|---|
| 1 | `spring-boot-starter-webflux` | 无 `WebClient` / `reactor.core` 引用；LLM 调用用 JDK 原生 `java.net.http.HttpClient`（`llm/OpenAiCompatibleService.java:12-14`） | "为响应式 LLM 引入"不成立，纯属空挂，徒增 reactor-netty 等一串 jar |
| 2 | `reactor-test` (test) | 无 `StepVerifier` 引用 | 与 #1 同源 |
| 3 | `spring-boot-starter-data-redis` | 无 `RedisTemplate` / `StringRedisTemplate` / `RedisConnectionFactory`；`application.yml:23-29` 的 redis 配置是"配置孤儿" | Java 层根本没用 Redis |
| 4 | `spring-boot-starter-cache` | 无 `@EnableCaching` / `@Cacheable` / `CacheManager` | Spring Cache 抽象未启用 |
| 5 | `caffeine` | 无 `Caffeine` / `CaffeineCacheManager` | "实际用 Caffeine"传言不实，业务缓存是手写内存 Map |

> 删除 Redis/cache/webflux 前确认无 actuator 健康检查依赖（本项目未引入 actuator，安全）。

### 前端 npm

| # | 依赖 | 证据 | 说明 |
|---|---|---|---|
| 6 | `vue-echarts ^8.0.1` | `frontend/package.json:21`；全仓 `vue-echarts` / `<v-chart>` / `VChart` 0 命中 | 自封装 `frontend/src/components/EChartsRenderer.vue`（调原生 `echarts/core`）已完全替代 |
| 7 | `highlight.js ^11.11.1` | `frontend/package.json:17`；全仓 0 import；代码高亮是手写正则（`components/ScriptTabs.vue:65`、`mining/TrainingCodeViewer.vue:158`） | 名义 Markdown 三件套实缺一件；marked + DOMPurify 链路成立，高亮未接入 |

### Python

| # | 依赖 | 证据 | 说明 |
|---|---|---|---|
| 8 | `scipy` | `docker/python/requirements.txt:3`；无任何动态脚本 import（仅出现在描述性文本 `PythonExecuteTool.java:31`、`AutoRepairHook.java:244`） | sklearn 会自带 scipy 传递依赖，无需显式列 |

---

## 二、版本不一致（重点：版本不同的同类引用）

### ECharts 前端 v6 vs 后端 SSR v5 — ✅ 已修复（2026-06-19）

| 端 | 文件 | 声明 | 实装 | 渲染路径 |
|---|---|---|---|---|
| 前端 | `frontend/package.json:15` | `echarts ^6.0.0` | 6.1.0 | 浏览器 Canvas |
| 后端 SSR | `backend/tools/echarts-ssr/package.json:8` | ~~`echarts ^5.5.1`~~ → `echarts ^6.0.0` | 6.1.0 | SSR SVG → Apache Batik 转 PNG → POI 嵌入 Word |

**问题**：数据库**仅存一份 `echartsOption`**（`entity/Chart.java`、`controller/ChartController.java:113`），被两个主版本分别解释：
- 前端用 v6 Canvas 渲染（用户在应用里看到的图）
- Word 报告用 v5 SVG 渲染（`WordReportService.java:318` → `ChartImageService.java:53,84` → `tools/echarts-ssr/render.mjs:4`）

**影响面**：v5→v6 有默认色板调整、轴标签防重叠、visualMap、新字段处理等差异，再叠加 SVG→PNG(Batik) 转换，前端图表与 Word 报告图表可能"看着不是一张图"。

**修复与验证**：
- 核实确认 Java 链路版本无关（`EChartsSsrRenderer` 只传 option JSON、读回 SVG 字符串；`SvgToPngConverter` 标准 Batik 无 v5 假设），render.mjs 用的 SSR API（`init(null,null,{ssr,renderer:svg})` + `renderToSVGString()`）在 v5.3+ 与 v6 完全一致，**render.mjs 零改动**。
- 仅改 `backend/tools/echarts-ssr/package.json` 的 `echarts` 为 `^6.0.0`，`npm install` 装上 6.1.0。
- 验证：① v6 render.mjs 跑通产出合法 SVG（exit 0）；② SVG 经 xmllint 校验良构（Batik 可解析）；③ 完整 E2E——插入测试图表后生成 Word 报告，后端日志 `[ECHARTS-SSR] 渲染成功 430ms (SVG 9675 字符)` → `[SVG2PNG] 转换成功 28992 字节`，docx 内含合法 PNG（`89504e47` 头，28KB）。全链路 v6 通过。

> 前后端现已统一 v6，同一份 echartsOption 渲染一致。

---

## 三、潜在 Bug（审计连带发现）

### 1. 多数据库支持"声明但未接通" + psycopg2 — ⚠️ 已澄清，PG 不涉及

> **2026-06-19 用户澄清更正**：本项目**不涉及 PostgreSQL**。系统库目标用**达梦 DM8**，业务库待定但非 PG。用户本机有 DM8 和 gbase 8a 驱动。下述 psycopg2 结论作废，改为记录真实的国产库驱动接线状况。

**代码声明**：`entity/DataSource.java:28-47` 的 switch 声明支持 mysql/postgresql/oracle/**dm(达梦)**/**gbase** 五种库（JDBC URL + 驱动类）。

**实际接线（带证据）**：
- ✅ **达梦 DM8 驱动**：`backend/lib/DmJdbcDriver8.jar`（含 `dm.jdbc.driver.DmDriver`，与 `DataSource.java:43` 一致）。
- ✅ **GBase 8a 驱动**：`backend/lib/gbase-connector-java-8.3.81.53.jar`（含 `com.gbase.jdbc.Driver`，与 `DataSource.java:44` 一致；用户提供，2026-06-19 纳入）。
- ✅ **两个驱动已接入 pom 并打进 fat jar**（2026-06-19 修复）：pom.xml 以 `<scope>system</scope>` + `<systemPath>${project.basedir}/lib/*.jar</systemPath>` 引入 DM + gbase，spring-boot-maven-plugin 设 `<includeSystemScope>true</includeSystemScope>`。验证：`mvn package` 后 fat jar 的 `BOOT-INF/lib/` 含 `DmJdbcDriver-8.jar` + `gbase-connector-java-8.3.81.53.jar`；jshell `Class.forName` 运行时两个驱动类均加载成功；后端启动 + API 正常。Dockerfile 无需改（用外部打好的 app.jar，已含驱动）。
- ⚠️ postgresql/oracle 驱动仍无（按用户方向 PG 不涉及；oracle 保留为声明，未接）。

> 至此动态**业务**数据源选「达梦/gbase」已可用（驱动在 classpath）。**系统库仍为 MySQL**，迁达梦见下。

**新增大项（系统库 MySQL→达梦迁移）**：用户要求"用达梦做系统库"，这是把 `smart_query` 系统库从 MySQL 迁到 DM8，**非一轮可完成**，需单独立项评估，关键风险点：
- `smart_query_seed.sql` 是 MySQL dump 语法，需转 DM 兼容 DDL（`JSON` 列类型如 `sq_chart.echarts_option`、`ON UPDATE CURRENT_TIMESTAMP`、反引号标识符、`LIMIT`、保留字、`SHOW TABLES` 等元信息查询差异）。
- MyBatis-Plus + 现有 mapper 对 DM 的兼容性（DM8 有 MySQL 兼容模式但需验证）。
- `application.yml` 数据源 URL/驱动切换；`docker-compose` 的 mysql 服务需替换为 DM。
- 动态业务数据源（`DataSourceManager`）若也用 DM/gbase，需对应驱动。
- **现状**：系统库仍跑 MySQL（`application.yml:12` jdbc:mysql、docker-compose mysql 服务）。迁移方向已定，待启动。

> psycopg2（原条目）作废：PG 不涉及，requirements 无需加 psycopg2。

### 2. Python 超时配置键不匹配（死配置）— ✅ 已修复（2026-06-19）

- 原：`tool/impl/PythonExecuteTool.java:23` 读取 `${python-tool.default-timeout-ms:60000}`，而 `application.yml` 的键是 `smart-query.python.default-timeout-ms`（在 `smart-query.python:` 块下）。
- 全仓核对：除这 1 处外，其余 **19 处** python 配置（`PythonExecutor`/`PythonCircuitBreaker`/`WebConfig`）一致用 `smart-query.python.*` 前缀。故**改代码侧这 1 处**对齐为 `${smart-query.python.default-timeout-ms:60000}`。
- 顺带：CLAUDE.md「已知问题」里关于 `docker-image`/`execution-mode` key 不匹配的说法已**过时**（`PythonExecutor:61,67` 现读 `smart-query.python.*` 且与 yml 一致），仅本超时 key 是真异常。
- 验证：`mvn compile` 通过，重启启动 1.80s，登录/受保护接口冒烟绿；代码 key（`PythonExecuteTool.java:23`）与 yml key（`application.yml:162`）现同名，配置可达。

### 3. requirements.txt 全无版本锁定 — ✅ 已修复（2026-06-19）

`docker/python/requirements.txt` 原全部裸包名，`FROM python:3.11-slim` 每次重建拉最新版，构建不可复现、且有 numpy 2.0 ABI / sklearn 跨版本 API 破坏风险。

**修复**：以 process 模式实测跑通的已知可用版本为下限锚点，锁定主版本上限：
```
numpy>=1.26,<2        # 关键：<2 避免 2.0 的 C 扩展 ABI 破坏
pandas>=2.0,<3
scikit-learn>=1.3,<2
xgboost>=1.7,<2
lightgbm>=4.0,<5
matplotlib>=3.7,<4
pymysql>=1.1,<2
sqlalchemy>=2.0,<3
joblib>=1.2,<2
```
**验证**：`pip install --dry-run` 无冲突；9/9 约束被本机已装版本（numpy 1.26.4、pandas 2.3.3、sklearn 1.3.2、xgboost 1.7.5、lightgbm 4.6.0、matplotlib 3.10.8、pymysql 1.1.0、sqlalchemy 2.0.20、joblib 1.2.0）满足，证明可解析。

---

## 四、建议核实（无定论，需人工或扫描器确认）

| 项 | 证据 | 待核实 |
|---|---|---|
| `springdoc-openapi-starter-webmvc-ui 2.3.0` | 全仓无 `@Operation` / `@Schema` 注解，仅 `application.yml:52-56` 配 swagger 路径、`WebConfig.java:102-104` 放行 | 只用了 UI 自动暴露，元数据极简；版本对 Spring Boot 3.4.1 偏旧。去留待定 |
| `jackson-datatype-jsr310` | 无显式 import，但 Spring Boot 自动配置会注册 `JavaTimeModule` | 确认接口返回的 `LocalDateTime` 确按 `yyyy-MM-dd HH:mm:ss` 序列化（理论正常，属自动配置间接生效） |
| 版本/CVE（凭知识，需 OWASP dependency-check 复核） | `batik 1.17`（历史 SSRF CVE，代码已 `SvgToPngConverter.java:38` `KEY_ALLOW_EXTERNAL_RESOURCES=false`）、`poi 5.2.5`、`mybatis-plus 3.5.6`、`spring-boot 3.4.1`（早期小版本） | 跑一次 dependency-check 核实安全公告 |

---

## 五、澄清：不是问题（避免误删）

- **jjwt 三件套**（`jjwt-api` / `jjwt-impl` / `jjwt-jackson`，均 0.12.6）：官方标准组合，impl/jackson 正确标 `runtime`，非冗余。
- **jackson-databind + jackson-datatype-jsr310**：Jackson 核心 + 日期模块的正常组合。
- **matplotlib**：CLAUDE.md 说"图表改用 ECharts SSR"仅指 Word 报告；`execute_python` 工具的 matplotlib 自动落盘链路（`PythonExecutor.java:278-309`，劫持 `plt.show`/`savefig`）独立存活，**必须保留**。
- 无重复 JSON 库（仅 jackson）、无重复 HTTP 客户端（仅 JDK HttpClient，webflux 虽在 pom 但未用）、无重复日志门面（logback）。

---

## 六、重复代码（跨文件同类引用，建议收敛）— URL 构造 ✅ 已收敛（2026-06-19）

| 重复点 | 出现位置 | 状态 |
|---|---|---|
| DB SQLAlchemy URL scheme 构造（`mysql+pymysql` / `postgresql+psycopg2` 的 switch） | 原 `MiningService`/`MiningPredictionService`/`PipelineService`/`PythonExecutor` 各 1 份 | ✅ 收敛到 `util/DbUrlUtil.buildSqlalchemyUrl(DataSource)` 单一实现；4 个私有方法已删、10 个调用点改调工具类。E2E 验证：pipeline preview-step 成功（Python 连库 exit=0），行为保留。后续加达梦只需在此 1 处加 case。 |
| DB URL 脱敏正则 `mysql\+pymysql://([^:]+):[^@]+@` | `PipelineService.java:1215,1571`、`MiningPipelineController.java:197`（3 处） | ⏸️ **推迟到达梦迁移**：DM 接入后正则必须扩展覆盖 `dm+dmPython://`，现在收敛届时仍要改；`DbUrlUtil.maskPassword` 已预留该方法位，DM 迁移时统一接入。 |
| 算法注册双源 | Java `mining/AlgorithmRegistry.java` 硬编码参数 + DB 表 `sq_algorithm` | 未动（CLAUDE.md 已列），新增算法需改两处——属产品扩展机制，非本轮依赖审计范畴。 |

---

## 七、审慎总结（按优先级）

| 优先级 | 行动 | 理由 |
|---|---|---|
| **高** | 对齐 ECharts 前后端到 v6 | 用户可见的图表一致性问题，唯一"版本不同的同类引用"硬伤 |
| **高** | 补 psycopg2 或禁用 pg 类型 | 潜在运行时崩溃 |
| **中** | 删 8 项确证冗余依赖 | 减体积、消除误导；删前各跑一次编译/构建验证 |
| **中** | requirements.txt 加版本锁 + 修超时 key | 构建可复现 + 修死配置 |
| **低** | 收敛重复 URL/正则代码；跑 CVE 扫描 | 可维护性 + 安全兜底 |

**整体评价**：依赖结构总体干净（无重复 JSON/HTTP/日志栈，jjwt/jackson 组合规范）。主要问题集中在两类——**挂载未用的依赖**（webflux / redis / cache / caffeine / vue-echarts / highlight.js / scipy 共 8 项）和 **echarts 前后端主版本不一致**。两者都是静态可见、低风险可修的，不是架构性问题。
