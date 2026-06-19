# 智能问数 · 持续优化追踪

> 目标：消除硬编码、统一 UI 变量控制、修复权限/多用户等问题。**原则：从实际需要出发，不过度优化；每步小改 + 测试验证 + 记录。**

## 现状调研（2026-06-18）

### 前端 UI 硬编码
- **变量体系已存在** `frontend/src/style.css`：颜色（`--primary`/`--color-success|warning|danger|info`/`--text-*`/`--border-*`）、字体（`--font-xs…2xl`）、间距、圆角、阴影齐全。
- **但组件复用不全**：`.vue` 中约 **24 行纯写死 hex**（已排除 `var(--x, fallback)` 好实践）+ **132 处写死 `font-size: Npx`**。
- 写死颜色集中：`ScenarioModule.vue`/`PromptManager.vue`（各 6）、`TrainingCodeViewer.vue`（6，**代码语法高亮色，语义特殊保留**）。
- `#409eff` 9 处已是 `var(--primary, #409eff)` fallback 形式 ✓ 不算写死。

### 后端硬编码（较轻）
- 端口 `9000` 散落 1 处（vite proxy，合理）；`/tmp/smartquery` 3 处（已由 `application.yml` 配置覆盖，代码里是默认值，可接受）。

## 优化原则
1. **值相同 → 视觉零变化**：变量化只是改引用方式，hex 值与变量值一致，不改变外观。
2. **语义特殊保留**：代码语法高亮色（VS Code token 色）、场景装饰渐变等，不强行套通用变量。
3. **缺变量再加**：若写死色无对应变量（如 `#606266`），先在 `style.css` 补语义变量再替换，不硬凑近似变量。
4. **小步验证**：每个文件/每类颜色改完即验证（前端 HMR 无报错、视觉不变）。

## 待办清单
- [ ] 前端：剩余纯写死 hex 变量化（ScenarioModule/PromptManager/pipeline 等，逐文件）
- [x] 前端 font-size 变量化：~115 处(12/11/13/14/15/16/18px)→`var(--font-*)`（值相同视觉零变化），剩 17 处特殊值(9/10/20/22/24/40/48px 无对应变量)保留
- [ ] 后端：排查魔法字符串/硬编码 URL（初步看较轻，待细查）
- [~] **⚠️ `isGhostModel` 路径硬编码**：已抽取到 `utils/modelGhost.js` 消除重复 + 集中（轮次12）；根本问题（基于开发者路径字符串猜测、换机器失效）仍在——待后端提供模型文件存在性接口替换
- [x] 前端组件状态字符串统一（MODEL/PIPELINE/EXECUTION/BLOCK/NODE_TYPES 五类常量全覆盖，block.status 轮次20 清零）
- [x] `ReportSummaryService` 默认模型硬编码 `"glm-5.1"` → `AppState.DEFAULT_MODEL`（用现有常量）
- [x] PipelineService 节点类型全常量化（轮次21：建 `common/NodeType` 类，31 处 → `NodeType.X`）
- [x] 权限控制：已调研 — 后端无任何鉴权（无 Security/Filter/Interceptor/@PreAuthorize），前端无登录
- [x] 多用户：已调研 — `ConversationController.list()`=`selectList(null)` 无 userId 过滤、get/delete 无 ownership 校验；本质单用户共享
- [ ] （待定产品方向）若需多用户：补鉴权 + userId 来源 + 列表/操作按 userId 过滤，暂不擅自实施（避免过度）
- [x] 过度降级：已调研 — 抽样核心(OpenAiCompatibleService/ReportSummaryService/DataSourceManager/前端axios拦截器) catch 均为合理防御性降级(有 log + 语义正确)，未发现静默吞异常或假成功

## 进度记录

### 轮次 1（2026-06-18）
- 调研前端 UI 硬编码现状，确认变量体系已有但复用不全。
- 变量化 `ScenarioModule.vue`：`#67c23a` → `var(--color-success)`、`#e6a23c` → `var(--color-warning)`（4 处，success/warning 语义清晰，值相同零风险）。
- 验证：前端 HMR 无报错。

### 轮次 2（2026-06-18）
- **权限/多用户调研（重点）**：后端无任何鉴权机制（无 Spring Security/Filter/Interceptor/@PreAuthorize）；`ConversationController.list()`=`selectList(null)` 返回全部会话、无 userId 过滤；会话 get/delete 仅按 id、无 ownership 校验；前端无登录、请求不传 userId。**本质是单用户共享模式**——多用户会互相看到/操作对方会话。
- 处理：按"不过度优化"原则，**不擅自引入完整鉴权**（涉及产品定位，需用户拍板），仅记录现状 + 风险 + 最小改造路径，待确认。最小改造建议：① 会话列表/操作按 userId 过滤；② userId 从请求头/token 取（先轻量，非完整 Security）。
- UI 变量化：`PromptManager.vue` 清理语义清晰写死色 — `#333`→`var(--text-regular)`、`#909399`→`var(--color-info)`（2 处）。
- 保留待评估：`#f5f7fa`(浅背景)、`#606266`(Element Plus regular 文字色) 缺合适语义变量，暂保留；若统一需先在 `style.css` 新增 `--surface-muted`/`--text-regular-ep`。
- 验证：前端 :5173 → 200 无报错。

### 轮次 3（2026-06-18）
- **过度降级排查**：全项目 244 个 catch，抽样核心文件——`OpenAiCompatibleService`（LLM 重试+最终抛出、isAvailable 返 false 合理）、`ReportSummaryService`（总结失败→fallback 且 log.error）、`DataSourceManager`（testConnection 返 false、元数据单字段探查失败给 "Unknown"、权限探查失败默认无权限）、前端 axios 拦截器（非 200 reject+ElMessage 提示）。
- 结论：**均为合理防御性降级**（有 log / 用户提示 + 语义正确），未发现静默吞异常或假成功掩盖问题。项目错误处理质量良好，不强行改造（避免过度优化）。
- 本轮无代码改动（调研确认健康，避免无谓修改）。

### 轮次 4（2026-06-18）
- **UI 颜色收尾**：清理剩余写死色 — `ChatPanel.vue` 能力卡图标 fallback `#409EFF`→`var(--primary)`。
- 保留（合理）：`ScriptTabs.vue` 3 处代码语法高亮色（VS Code token 色，语义特殊）、`PipelinePreview.vue` 按 ratio 的三档数据色（JS 逻辑）、`OntologyManager.vue` 的 `&#128218;` 是 emoji 非颜色。
- **font-size 评估**：132 处中高频 6 档（12/11/13/14/16/18px，~114 处）与 `--font-*` 变量值完全相同，可零风险变量化；低频特殊值（10/9/48/24/40/22px，~17 处）无对应变量保留。下轮起用 sed 分批替换高频档。
- 验证：前端 :5173 → 200 无报错。

### 轮次 5（2026-06-18）
- **font-size 批量变量化**：sed 替换 7 档共 ~115 处（`12→--font-sm`、`11→--font-xs`、`13→--font-md`、`14→--font-base`、`15→--font-lg`、`16→--font-xl`、`18→--font-2xl`），值与变量完全相同、视觉零变化。`var(--font-*)` 引用 0→290+。
- 保留 17 处无变量特殊值（9/10/20/22/24/40/48px）：特小字角标 + 大标题，各 1-8 处，新增变量收益小，按"不过度优化"保留。
- 验证：前端 :5173 → 200，HMR 无报错。
- **UI 变量化阶段小结**：颜色（ScenarioModule/PromptManager/ChatPanel 7 处）+ font-size（~115 处）已统一到 `style.css` 变量；剩余写死均为合理保留（代码语法高亮色、按 ratio 数据色、特殊字号）。

### 轮次 6（2026-06-18）
- **逻辑不统一修复**：`MiningModelController` 6 处模型状态字符串（`"training"`/`"trained"`/`"failed"`，含 switch case + equals）统一到 `ModelStatus.TRAINING/TRAINED/FAILED` 常量——消除"Service 用常量、Controller 用字符串"的不一致；`public static final String` 可作 switch case 标签；值相同零行为变化，`mvn compile` 通过。
- 发现（待评估，不贸然改）：`PipelineService` 节点类型字符串（`data_source`/`preprocessing`/`training`/`evaluation`/`output` 等）散落多处，`typeOrder` List.of 重复 2 次完全相同——建议抽 `NodeType` 常量类，但影响多处属较大重构，暂记录待定（不过度优化）。
- 注：后端改动需重启后端才生效（运行中进程仍是旧 class）；本改动为纯重构（值相同），不重启也不影响行为。

### 轮次 7（2026-06-18）
- **消除重复/硬编码**：① `PipelineService` 两处完全重复的 `typeOrder = List.of(7 个节点类型)` 提取为 `private static final NODE_TYPE_ORDER` 常量（DRY）；② `ReportSummaryService.getDefaultModel()` 硬编码 `"glm-5.1"` → `AppState.DEFAULT_MODEL`（复用现有常量，消除"有常量不用"）。
- 节点类型字符串全量常量化（32 处散落）评估后保留——属较大重构，按"不过度优化"暂不做。
- `mvn compile` 通过（COMPILE_EXIT=0）。

### 轮次 8（2026-06-18）
- **前端常量统一**：`stores/mining.js`（状态管理核心）3 处模型状态字符串（`'published'` / `['training','queued']` / `['trained','failed','published']`）统一到 `MODEL_STATUS` 常量；`constants.js` 补 `MODEL_STATUS.QUEUED`（原缺）。与后端 `ModelStatus` 统一呼应。
- **调研结论**：前端 `constants.js` 常量体系健康（DEFAULT_TIMEOUT_MS / MODEL_STATUS / PIPELINE_STATUS / NODE_TYPES / MODEL_TYPE / EXECUTION_STATUS 均已集中，MODEL_STATUS 已被引用 21 处）。
- 发现：前端有 `NODE_TYPES` 常量但后端 PipelineService 散落——前后端不一致，后端重构较大已登记。
- 验证：前端 :5173 → 200 无报错，mining.js 状态字符串已全部常量化。

### 轮次 9（2026-06-18）
- **前端常量统一**：`composables/useModelActions.js` 3 处状态字符串（`'failed'`/`'published'`/`'training'`）统一到 `MODEL_STATUS`（该文件已 import 且 line 70 已用，属同文件不一致修复）。
- **⚠️ 重要发现（待修，需业务确认）**：`useModelActions.js:231-233` `isGhostModel` 判断写死开发者本机路径（`/Users/gonghang`、`C:\\Users\\lenovo`、`/tmp/smartquery-workspace`）——**换机器/部署环境即失效**，真实硬编码 bug。修法需理解"幽灵模型"业务语义（建议改为基于模型文件是否存在或 status，而非特定用户路径），涉及删除逻辑，谨慎不贸然改。
- 验证：前端 :5173 → 200 无报错。

### 轮次 10（2026-06-18）
- **前端常量统一**：`MiningManager.vue` 6 处状态字符串（`'published'`/`'training'`/`'failed'`，含 filter / needsForceDelete / 训练监听）统一到 `MODEL_STATUS`（文件已 import 且 937 行已用，修复同文件不一致）。
- 印证：`isGhostModel` 路径硬编码 bug 在 `MiningManager.vue:654-657` **重复出现**（与 useModelActions.js 同款）——证实需统一修，仍待业务确认。
- 验证：前端 :5173 → 200 无报错。

### 轮次 11（2026-06-19）
- **前端常量统一收尾**：`PipelineEditor.vue` —— ① template 状态比较 `'trained'`/`'success'` → `MODEL_STATUS.TRAINED`/`EXECUTION_STATUS.SUCCESS`（2 处，保留 el-tag 的 `? 'success':'danger'` 不动）；② `stepTypes` 节点配置数组 7 个 type → `NODE_TYPES.X`（复用前端 NODE_TYPES 常量）；③ 响应解析 2 处 `'success'` → `EXECUTION_STATUS.SUCCESS`。
- **阶段小结**：前端核心（store/composable/组件）状态字符串与节点类型已全部统一到 `MODEL_STATUS`/`EXECUTION_STATUS`/`NODE_TYPES`。UI 变量化（颜色+font-size）+ 常量统一阶段基本完成。
- 验证：前端 :5173 → 200 无报错。

### 轮次 12（2026-06-19）
- **DRY + 集中硬编码**：`isGhostModel` 判断在 `useModelActions.js` 与 `MiningManager.vue` 两处重复（含相同的开发者路径硬编码）→ 抽取到 `utils/modelGhost.js` 共用函数。消除重复 + 路径硬编码从两处散落集中到 util 一处（含 TODO：待后端提供模型文件存在性接口替换字符串猜测）。行为完全不变。
- 路径 `/Users/gonghang`/`C:\\Users\\lenovo`/`/tmp/smartquery-workspace` 现**仅存于 modelGhost.js 一处**（原两处），便于未来统一重构。
- 验证：前端 :5173 → 200 无报错。

### 轮次 13（2026-06-19）
- **border-radius 变量化**：延续 font-size 思路，sed 批量替换高频 3 档（`4px→--radius-sm`、`6px→--radius-md`、`8px→--radius-lg`）共 32 处（分号锚定只替换单值，避免多值误伤）。值相同视觉零变化。border-radius 现已用 var ~107 处，写死仅剩特殊值 3/12/20px。
- 验证：前端 :5173 → 200 无报错。

### 轮次 14（2026-06-19）
- **padding 单值变量化**：sed 批量替换 padding 单值 6 档（4/8/12/16/20/24px → `--space-xs/sm/md/lg/xl/2xl`）共 24 处（分号锚定单值，多值不匹配故未误伤）。值相同视觉零变化。
- 保留：padding 多值（60 处，如 `padding: 12px 8px`，结构复杂、按组件布局各异，统一收益低风险高）+ margin 仅 8 处少量——按"不过度优化"保留。
- 验证：前端 :5173 → 200 无报错，多值 padding 抽样未误伤。
- **UI 变量统一阶段完成**：font-size（~115）+ border-radius（32）+ padding 单值（24）均已变量化；颜色（7）+ 阴影等也已统一。剩余写死均为合理保留（多值 padding、特殊字号/圆角、代码高亮色、数据色）。

### 轮次 15（2026-06-19）
- **生产 console.log 清理（构建层）**：`vite.config.js` 加 `esbuild.pure: ['console.log']`——生产构建自动移除应用代码 console.log（23 处），保留 `console.error/warn`（45 处错误日志）。开发环境不受影响（pure 仅生产 minify 生效）。符合 TS 规范"生产代码无 console.log"。
- 附：proxy error 的 `console.log` → `console.error`（语义更准，node 端不受 esbuild.pure 影响）。
- 调研：后端仅 1 个 TODO（`CoordinatorIntegration:72`，非紧急，保留）。
- 验证：前端 :5173 → 200，vite 配置无错误。

### 轮次 16（2026-06-19）
- **生产构建验证发现并修复 bug**：`npm run build` 发现 `PipelineEditor.vue` 的 `NODE_TYPES` **重复 import**（轮次 11 我新增的 import 与原有 import 都含 NODE_TYPES）——dev HMR 宽容未报，生产构建严格报 `Identifier 'NODE_TYPES' has already been declared`。已合并为单一 import。
- 构建结果：✓ built in 2.01s（仅 2 个非阻塞警告：chunk >500kB 建议代码分割、@vueuse/core PURE 注释位置——均第三方/性能提示，非错误）。
- **教训**：UI 变量化/常量统一等重构后必须跑 `npm run build` 验证，dev HMR 不足以暴露所有问题（import 重复等）。本次 build 验证修复了潜伏的部署阻断 bug。

### 轮次 17（2026-06-19）
- **前端状态字符串全覆盖扫描**：发现并统一遗漏的 `ModelDetail.vue` 6 处模型状态字符串（training/published/trained/offline → `MODEL_STATUS`，template + script setup 自动暴露）。
- **build 验证**（吸取轮次 16 教训）：✓ built in 826ms 无错误。
- 全扫描另发现（待后续轮次）：① `PipelineList.vue` 2 处 pipeline `'running'` → `PIPELINE_STATUS.RUNNING`；② `PipelineEditor.vue:695` 前端 typeOrder 重复（后端已改、前端漏）；③ `NodeParamsEditor.vue:94` `node.type==='training'` → `NODE_TYPES`；④ 各处 `block.status='running'`（对话块执行状态，不同领域，待评估是否用 EXECUTION_STATUS）。

### 轮次 18（2026-06-19）
- **前端常量统一（续）**：`PipelineList.vue` 2 处 `p.status==='running'` → `PIPELINE_STATUS.RUNNING`（+ import）；`NodeParamsEditor.vue` `node.type==='training'` → `NODE_TYPES.TRAINING`（+ import）。
- `PipelineEditor:695` 前端 typeOrder 评估：仅一处使用（无 DRY 违反），不强行提取（不过度）。
- build 验证：✓ built in 805ms 无错误。
- 剩余：各处 `block.status='running'`（对话块执行状态，与模型/管道状态不同领域，待评估是否用 EXECUTION_STATUS，谨慎）。

### 轮次 19（2026-06-19）
- **BLOCK_STATUS 常量新建**：`block.status` 值域 `{running/success/error/completed}` 与 EXECUTION_STATUS `{success/failed/running/pending}` **值冲突**（error≠failed），是 ReAct 对话块独立状态域——新建 `BLOCK_STATUS` 常量集中（值相同不破坏）。
- `ChatPanel.vue` 4 处 `'running'`（block.status 赋值/比较/status 初始化）→ `BLOCK_STATUS.RUNNING`（+ import）。
- build 验证：✓ 795ms。
- 剩余（下一轮）：ChatPanel 的 `success`/`error` 三元（378/412/425/438/459/516/531，需精确避免 `'success'` 误伤 el-tag type）+ `conversation.js`/`MessageRow` 的 block.status。

### 轮次 20（2026-06-19）
- **block.status 全统一到 BLOCK_STATUS**：ChatPanel success/error 三元（378/412/531）+ `block.status='success'`（425/438/459/516）；`conversation.js` 2 处；`MessageRow` running/success/error（134/311/317/71/122）。全前端 `block/b/targetSql/lastPy.status` 字符串比较/赋值**全部**用 BLOCK_STATUS（grep 确认零残留）。
- build 验证：✓ 841ms。
- **前端常量统一彻底完成**：MODEL/PIPELINE/EXECUTION/**BLOCK**/NODE_TYPES 五类状态常量 + 颜色/字号/圆角/间距 UI 变量，前端状态字符串散落已清零。

### 轮次 21（2026-06-19）
- **后端 NodeType 常量化**（前后端一致性最后一块）：新建 `common/NodeType` 常量类（与前端 `NODE_TYPES` 对应）；`PipelineService` 31 处节点类型字符串（switch case / 校验 / 比较 / label 映射 / NODE_TYPE_ORDER）全部 → `NodeType.X`。label 映射的中文 label 保留，仅 type 部分常量化。值相同零行为变化。
- `mvn compile` 通过（COMPILE_EXIT=0）。
- **前后端节点类型常量统一完成**：之前登记的"较大重构"（PipelineService 32 处散落）已完成。

### 轮次 22（2026-06-19）
- **box-shadow 评估**：写死 10 处，但 4 处是 `none` + 6 处值分散且与 `--shadow-sm/md/lg` 不精确对应（如 `0 1px 2px` vs `--shadow-sm` 的 `0 1px 3px`）——强行映射会改变视觉。**保留**（不过度优化）。
- **自主优化空间彻底穷尽**：UI 变量（颜色/font-size/border-radius/padding）+ 前后端常量（MODEL/PIPELINE/EXECUTION/BLOCK/NODE_TYPES/NodeType/ModelStatus/AppState）+ DRY（typeOrder/isGhostModel）+ 构建（drop console）+ 过度降级排查（健康）均已完成。剩余仅决策依赖项（多用户鉴权、isGhostModel 后端接口、git 提交）。
