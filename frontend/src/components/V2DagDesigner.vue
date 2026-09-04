<template>
  <div :class="['page-container', 'dag-designer', { embedded: props.embedded }]">
    <header class="page-header dag-header">
      <button v-if="!props.embedded" class="back-btn" @click="emit('close')"><span class="back-arrow">&larr;</span> 返回问数</button>
      <div v-if="!props.embedded" class="dag-heading"><h2 class="page-title">流程编排</h2></div>
      <el-select v-model="flowId" placeholder="选择流程" filterable class="flow-select" @change="loadVersions">
        <el-option v-for="flow in flows" :key="flow.id" :label="flow.name" :value="flow.id" />
      </el-select>
      <el-button @click="createVisible=true">新建流程</el-button>
      <el-select v-if="flowId" v-model="selectedVersionId" clearable placeholder="新草稿" class="version-select" @change="loadSelectedVersion">
        <el-option v-for="version in versions" :key="version.id" :label="`流程版本 ${version.versionNo} · #${version.id}`" :value="version.id" />
      </el-select>
      <span class="spacer" />
      <el-button :loading="busy==='validate'" @click="validateCurrent">校验</el-button>
      <el-button type="primary" :loading="busy==='save'" :disabled="!flowId || !nodes.length" @click="saveVersion">保存不可变版本</el-button>
      <el-button :disabled="!flowId" @click="historyVisible=true">运行记录</el-button>
      <el-button type="success" :disabled="!selectedVersionId" @click="trialVisible=true">试运行</el-button>
    </header>

    <main class="designer-layout">
      <aside class="operator-palette">
        <div class="panel-title"><strong>统一算子库</strong><el-button size="small" link type="primary" @click="emit('createOperator')">+ 创建算子</el-button></div>
        <div class="palette-note"><span>仅展示已发布版本</span><el-tag size="small" type="success">版本锁定</el-tag></div>
        <el-input v-model="catalogSearch" clearable placeholder="搜索算子" size="small" />
        <el-radio-group v-model="typeFilter" size="small" class="type-filter">
          <el-radio-button value="">全部</el-radio-button><el-radio-button value="DATA">加工</el-radio-button>
          <el-radio-button value="RULE">规则</el-radio-button><el-radio-button value="ML">机器学习</el-radio-button>
          <el-radio-button value="AGENT">智能体</el-radio-button><el-radio-button value="OUTPUT">输出</el-radio-button>
        </el-radio-group>
        <div class="palette-list" v-loading="catalogLoading">
          <div v-for="group in catalogGroups" :key="group.type" class="palette-group">
            <div class="palette-group-title">{{ typeLabel(group.type) }} <span>{{ group.items.length }}</span></div>
            <button v-for="item in group.items" :key="item.operatorVersionId" draggable="true" type="button"
              :class="['palette-item', `type-${item.operatorType.toLowerCase()}`]"
              @dragstart="startCatalogDrag($event,item)" @click="quickAdd(item)">
              <span class="palette-icon">{{ typeIcon(item.operatorType) }}</span>
              <span class="palette-main"><strong>{{ item.name }}</strong><small>版本 {{ item.versionNo }} · {{ item.runtimeProfileCode }}</small></span>
              <el-tag v-if="item.operatorType==='OUTPUT'" size="small" type="success">{{ item.metadata?.outputKind }}</el-tag>
            </button>
          </div>
          <el-empty v-if="!catalogLoading && !filteredCatalog.length" description="暂无已发布算子" :image-size="60" />
        </div>
      </aside>

      <section class="canvas-shell">
        <div class="canvas-toolbar">
          <span>{{ nodes.length }} 节点 · {{ edges.length }} 连线</span>
          <span v-if="selectedVersionId" class="locked-note">当前展示不可变流程版本；编辑后保存会产生新版本</span>
          <el-button size="small" text @click="newDraft">清空画布</el-button>
        </div>
        <V2DagCanvas :nodes="nodes" :edges="edges" :selected-node-id="selectedNode?.id" :selected-edge="selectedEdge"
          :connection-source="connectionSource" :node-statuses="nodeStatuses" :edge-contracts="edgeContractsByKey"
          @drop-operator="addOperator" @select-node="selectNode" @select-edge="selectEdge" @move-node="moveNode"
          @begin-connection="beginConnection" @complete-connection="completeConnection" @cancel-connection="connectionSource=null" />
      </section>

      <aside class="inspector">
        <div class="panel-title"><strong>配置与校验</strong></div>
        <template v-if="selectedNode">
          <el-form label-position="top" size="small">
            <el-form-item label="节点名称"><el-input v-model="selectedNode.name" :disabled="selectedNode.systemManaged" /></el-form-item>
            <el-form-item label="算子版本"><el-input :model-value="`${selectedNode.operatorName} v${selectedNode.versionNo} (#${selectedNode.operatorVersionId})`" disabled /></el-form-item>
            <el-form-item label="实现类型"><el-input :model-value="selectedNode.implementationType || '未声明'" disabled /></el-form-item>
            <el-form-item label="运行时"><el-input :model-value="selectedNode.runtimeProfileCode" disabled /></el-form-item>
            <div v-if="selectedNode.implementationType==='SQL_AST'" class="capability-panel sql-policy">
              <strong>受权 SQL 数据入口</strong>
              <span>数据源 #{{ selectedNode.metadata?.dataSourceId }} · 最多 {{ selectedNode.metadata?.maxRows || 1000 }} 行</span>
              <div><el-tag v-for="table in selectedNode.metadata?.allowedTables || []" :key="table" size="small">{{ table }}</el-tag></div>
              <small>SQL、数据源和表白名单由算子版本锁定；该节点必须是 DAG 根节点，节点配置仅可提供命名参数值。</small>
            </div>
            <div v-if="selectedNode.implementationType==='AGENT_POLICY'" class="capability-panel agent-policy">
              <strong>受控智能体策略</strong>
              <span>{{ selectedNode.metadata?.model }} · {{ selectedNode.metadata?.maxTurns || 3 }} 轮 · 最多 {{ selectedNode.metadata?.maxToolCalls || 4 }} 次工具调用</span>
              <div><el-tag v-for="tool in selectedNode.metadata?.allowedTools || []" :key="tool" size="small" type="warning">{{ tool }}</el-tag></div>
              <small>模型、只读工具白名单及执行预算均由已发布版本锁定，画布节点不可覆盖。</small>
            </div>
            <el-form-item v-if="selectedNode.operatorType==='OUTPUT'" label="输出方式"><el-tag type="success">{{ selectedNode.metadata?.outputKind }}</el-tag><span class="field-note">输出节点只能作为终点</span></el-form-item>
            <el-form-item label="节点覆盖配置（JSON）"><el-input v-model="nodeConfigText" type="textarea" :rows="10" class="json-editor" :disabled="selectedNode.operatorType==='OUTPUT'" @blur="commitNodeConfig" /><span v-if="selectedNode.operatorType==='OUTPUT'" class="field-note">输出展示规格属于已发布版本，画布中不可覆盖。</span><span v-else class="field-note">可设置 nodeTimeoutSeconds（1–3600），保存后随流程版本锁定。</span></el-form-item>
          </el-form>
          <el-button v-if="!selectedNode.systemManaged" type="danger" plain size="small" @click="removeNode(selectedNode.id)">删除节点</el-button>
          <el-alert v-else type="info" :closable="false" title="这是旧流程版本中的平台托管节点；新流程不再自动补充线索输出。" />
        </template>
        <template v-else-if="selectedEdge">
          <div class="edge-detail"><strong>{{ nodeName(selectedEdge.source) }}</strong><span>→</span><strong>{{ nodeName(selectedEdge.target) }}</strong></div>
          <el-form label-position="top" size="small" class="edge-form">
            <el-form-item label="记录映射模式">
              <el-radio-group v-model="selectedEdge.mappingMode">
                <el-radio-button value="MERGE">保留并映射</el-radio-button>
                <el-radio-button value="PROJECT">仅映射字段</el-radio-button>
              </el-radio-group>
              <span class="field-note edge-mode-note">血缘与证据字段始终由平台保留。</span>
            </el-form-item>
            <div class="schema-summary">
              <div><strong>来源输出字段</strong><span v-if="!sourceFields.length">Schema 未声明</span><el-tag v-for="field in sourceFields.slice(0,8)" :key="field.path" size="small">{{ field.path }}</el-tag></div>
              <div><strong>目标输入字段</strong><span v-if="!targetFields.length">Schema 未声明</span><el-tag v-for="field in targetFields.slice(0,8)" :key="field.path" size="small" :type="field.required?'danger':'info'">{{ field.path }}{{ field.required?' *':'' }}</el-tag></div>
            </div>
            <el-form-item label="字段映射">
              <div v-for="(mapping,index) in selectedEdge.fieldMappings" :key="index" class="mapping-item">
                <div class="mapping-row">
                  <el-select v-model="mapping.from" filterable allow-create default-first-option placeholder="来源字段">
                    <el-option v-for="field in sourceFields" :key="field.path" :label="field.path" :value="field.path" />
                  </el-select>
                  <span class="mapping-arrow">→</span>
                  <el-select v-model="mapping.to" filterable allow-create default-first-option placeholder="目标字段">
                    <el-option v-for="field in targetFields" :key="field.path" :label="field.path" :value="field.path" />
                  </el-select>
                  <el-button text type="danger" @click="removeFieldMapping(index)">×</el-button>
                </div>
                <div class="mapping-options"><el-checkbox v-model="mapping.required" title="运行时缺少来源字段立即失败">来源必填</el-checkbox><el-input v-model="mapping.defaultValueText" clearable placeholder="默认值 JSON（可选）" /></div>
              </div>
              <el-button plain class="add-mapping" @click="addFieldMapping">+ 添加字段映射</el-button>
            </el-form-item>
          </el-form>
          <el-alert v-if="selectedEdgeContract" :type="selectedEdgeContract.compatible?'success':'error'" :closable="false"
            :title="selectedEdgeContract.compatible?'边数据契约兼容':'边数据契约不兼容'">
            <p v-for="message in selectedEdgeContract.errors" :key="message">{{ message }}</p>
            <p v-for="message in selectedEdgeContract.warnings" :key="message">{{ message }}</p>
          </el-alert>
          <div class="edge-actions"><el-button size="small" @click="validateCurrent">校验此边</el-button><el-button type="danger" plain size="small" @click="removeSelectedEdge">删除连线</el-button></div>
        </template>
        <el-empty v-else description="选择节点或连线查看配置" :image-size="64" />

        <div class="validation-box">
          <div class="validation-head"><strong>预检结果</strong><el-tag v-if="validation" :type="validation.valid?'success':'danger'">{{ validation.valid?'通过':'未通过' }}</el-tag></div>
          <template v-if="validation"><p v-for="error in validation.errors" :key="error" class="error-line">{{ error }}</p><p v-for="warning in validation.warnings" :key="warning" class="warning-line">{{ warning }}</p><div v-if="validation.valid" class="levels">执行层级：{{ validation.executionLevels?.map(level=>level.join(' + ')).join(' → ') }}</div></template>
          <p v-else class="muted">保存或试运行前先执行 DAG 结构校验。</p>
        </div>
      </aside>
    </main>

    <el-dialog v-model="createVisible" title="新建 V2 流程" width="460px"><el-form label-position="top"><el-form-item label="流程名称"><el-input v-model="newFlow.name" /></el-form-item><el-form-item label="唯一编码"><el-input v-model="newFlow.code" placeholder="lowercase_flow_code" /></el-form-item><el-form-item label="说明"><el-input v-model="newFlow.description" type="textarea" /></el-form-item></el-form><template #footer><el-button @click="createVisible=false">取消</el-button><el-button type="primary" :loading="busy==='create'" @click="createNewFlow">创建</el-button></template></el-dialog>

    <FlowRunHistoryDrawer v-model="historyVisible" :flow-id="flowId" :flow-name="selectedFlowName" :versions="versions" />

    <el-dialog v-model="trialVisible" title="流程试运行" width="920px" destroy-on-close>
      <div class="trial-layout">
        <section class="trial-input"><el-alert :closable="false" type="info" title="输入只用于有界试运行；正式输出会在整条 DAG 成功后原子提交。" /><el-input v-model="trialInputText" type="textarea" :rows="18" class="json-editor" /><el-button type="primary" :loading="busy==='trial'" @click="startTrial">开始运行流程版本 #{{ selectedVersionId }}</el-button></section>
        <section class="trial-status">
          <div v-if="currentRun" class="run-summary"><span><strong>运行 #{{ currentRun.id }}</strong><small v-if="currentRun.attemptNo">第 {{ currentRun.attemptNo }} 次执行<span v-if="currentRun.recoveryCount"> · 已恢复 {{ currentRun.recoveryCount }} 次</span></small></span><span class="run-actions"><el-tag :type="runStatusType(currentRun.status)">{{ currentRun.status }}</el-tag><el-button v-if="['QUEUED','RUNNING'].includes(currentRun.status)" size="small" type="danger" plain :loading="canceling" @click="cancelCurrentRun">取消运行</el-button></span></div>
          <div v-for="node in nodes" :key="node.id" class="run-node-row">
            <span class="run-node-name"><strong>{{ node.name }}</strong><small v-if="nodeRunFor(node.id)">节点运行 #{{ nodeRunFor(node.id).id }}</small></span>
            <span class="run-actions">
              <el-tag size="small" :type="runStatusType(nodeStatuses[node.id])">{{ nodeStatuses[node.id] || 'WAITING' }}</el-tag>
              <el-button v-if="canReplay(node.id)" size="small" plain :loading="replayBusy===node.id" @click="startNodeReplay(node.id)">审计回放</el-button>
              <el-tag v-if="latestReplay(node.id)?.archiveStatus === 'ARCHIVED'" size="small" type="info">回放 #{{ latestReplay(node.id).id }} 已归档</el-tag>
              <el-button v-else-if="latestReplay(node.id)" size="small" text type="primary" @click="openReplay(latestReplay(node.id).id)">查看 #{{ latestReplay(node.id).id }}</el-button>
            </span>
          </div>
          <el-alert v-if="currentRun?.errorMessage" type="error" :closable="false" :title="currentRun.errorMessage" />
        </section>
      </div>
      <div v-if="runOutputs.length" class="run-output-tabs" v-loading="artifactLoading">
        <div class="run-output-toolbar">
          <el-select v-model="selectedArtifactId" size="small" @change="changeArtifact"><el-option v-for="item in runOutputs" :key="item.id" :label="`${item.outputKind} 结果 #${item.id}`" :value="item.id" /></el-select>
          <span v-if="artifactView">{{ artifactView.totalRows }} 条 · 第 {{ artifactPage + 1 }} 页</span>
          <el-button size="small" :disabled="artifactPage===0 || artifactLoading" @click="previousArtifactPage">上一页</el-button>
          <el-button size="small" type="primary" plain :disabled="!artifactView?.hasMore || artifactLoading" @click="nextArtifactPage">下一页</el-button>
        </div>
        <OutputArtifactViewer v-if="artifactView" :key="`${selectedArtifactId}-${artifactSort?.field || 'row'}-${artifactSort?.direction || 'ASC'}`"
          :view="artifactView" server-driven :sort="artifactSort" @sort-change="changeArtifactSort" />
      </div>
    </el-dialog>

    <el-dialog v-model="replayVisible" title="节点级可审计回放" width="1080px" destroy-on-close @closed="clearReplayPoll">
      <template v-if="replayDetail">
        <div class="replay-head">
          <span><strong>{{ replayDetail.replay.replayNo }}</strong><small>原节点 {{ replayDetail.replay.nodeId }} · 原运行 #{{ replayDetail.replay.sourceRunId }}</small></span>
          <span class="run-actions"><el-tag :type="runStatusType(replayDetail.replay.status)">{{ replayDetail.replay.status }}</el-tag><el-button v-if="['QUEUED','RUNNING'].includes(replayDetail.replay.status)" size="small" type="danger" plain @click="cancelReplay">取消回放</el-button></span>
        </div>
        <el-alert v-if="replayDetail.replay.errorMessage" type="error" :closable="false" :title="replayDetail.replay.errorMessage" />
        <el-descriptions class="replay-binding" :column="2" border size="small">
          <el-descriptions-item label="流程版本">#{{ replayDetail.replay.flowVersionId }} · {{ shortHash(replayDetail.replay.flowContentHash) }}</el-descriptions-item>
          <el-descriptions-item label="算子版本">#{{ replayDetail.replay.operatorVersionId }} · {{ shortHash(replayDetail.replay.operatorVersionContentHash) }}</el-descriptions-item>
          <el-descriptions-item label="固定运行时">#{{ replayDetail.replay.runtimeProfileId }} · {{ shortHash(replayDetail.replay.runtimeImageDigest) }}</el-descriptions-item>
          <el-descriptions-item label="固定输入">{{ shortHash(replayDetail.replay.inputHash) }}</el-descriptions-item>
          <el-descriptions-item label="原输出">{{ shortHash(replayDetail.replay.expectedOutputHash) || '无基线' }}</el-descriptions-item>
          <el-descriptions-item label="回放输出">{{ shortHash(replayDetail.replay.outputHash) || '等待执行' }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="replayDetail.replay.status==='SUCCESS'">
          <div class="diff-cards">
            <div :class="['diff-card', replayDetail.diff.exactMatch?'exact':'drift']"><strong>{{ replayDetail.diff.exactMatch ? '完全一致' : '存在差异' }}</strong><small>输出哈希比对</small></div>
            <div class="diff-card"><strong>{{ replayDetail.diff.added ?? 0 }}</strong><small>新增记录</small></div>
            <div class="diff-card"><strong>{{ replayDetail.diff.removed ?? 0 }}</strong><small>缺失记录</small></div>
            <div class="diff-card"><strong>{{ replayDetail.diff.changed ?? 0 }}</strong><small>变化记录</small></div>
          </div>
          <el-alert v-if="replayDetail.diff.baselineAvailable===false" type="warning" :closable="false" :title="replayDetail.diff.reason" />
          <el-tabs class="replay-tabs">
            <el-tab-pane label="差异明细">
              <el-table :data="replayDetail.diff.samples || []" max-height="350" empty-text="记录级内容一致或没有可比较基线">
                <el-table-column type="expand"><template #default="scope"><div class="compare-json"><section><strong>原输出记录</strong><pre>{{ formatJson(scope.row.original) }}</pre></section><section><strong>回放记录</strong><pre>{{ formatJson(scope.row.replay) }}</pre></section></div></template></el-table-column>
                <el-table-column prop="key" label="记录身份" min-width="220" show-overflow-tooltip />
                <el-table-column prop="kind" label="差异类型" width="110"><template #default="scope"><el-tag size="small" :type="scope.row.kind==='CHANGED'?'warning':scope.row.kind==='ADDED'?'success':'danger'">{{ scope.row.kind }}</el-tag></template></el-table-column>
                <el-table-column label="变化字段" min-width="240"><template #default="scope">{{ Object.keys(scope.row.changedFields || {}).join('、') || '整条记录' }}</template></el-table-column>
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="原输出样本"><pre class="sample-json">{{ formatJson(replayDetail.originalSample) }}</pre></el-tab-pane>
            <el-tab-pane label="回放输出样本"><pre class="sample-json">{{ formatJson(replayDetail.replaySample) }}</pre></el-tab-pane>
            <el-tab-pane label="指标差异"><pre class="sample-json">{{ formatJson(replayDetail.diff.metricChanges || {}) }}</pre></el-tab-pane>
          </el-tabs>
        </template>
        <el-empty v-else-if="['QUEUED','RUNNING'].includes(replayDetail.replay.status)" description="正在使用固定快照执行，不会提交线索或输出工件" :image-size="70" />
      </template>
      <el-skeleton v-else :rows="8" animated />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import V2DagCanvas from './V2DagCanvas.vue'
import OutputArtifactViewer from './OutputArtifactViewer.vue'
import FlowRunHistoryDrawer from './FlowRunHistoryDrawer.vue'
import { cancelNodeReplay, cancelRun, createFlow, createFlowVersion, createNodeReplay, fetchFlowVersions, fetchFlows, fetchNodeReplay, fetchNodeReplays, fetchPublishedOperatorCatalog, fetchRun, fetchRunNodes, fetchRunOutputs, queryOutputView, submitModelVersionApproval, submitTrialRun, validateDag } from '../api/orchestration.js'

const props = defineProps({
  initialOperatorVersionId: { type: Number, default: null },
  embedded: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'createOperator'])
const catalog = ref([]), flows = ref([]), versions = ref([]), flowId = ref(null), selectedVersionId = ref(null)
const nodes = ref([]), edges = ref([]), selectedNode = ref(null), selectedEdge = ref(null), connectionSource = ref(null)
const catalogSearch = ref(''), typeFilter = ref(''), catalogLoading = ref(false), busy = ref(''), validation = ref(null)
const nodeConfigText = ref('{}'), createVisible = ref(false), trialVisible = ref(false), historyVisible = ref(false)
const newFlow = reactive({ name: '', code: '', description: '' })
const currentRun = ref(null), runNodes = ref([]), nodeStatuses = ref({}), runOutputs = ref([]), selectedArtifactId = ref(null), artifactView = ref(null)
const artifactPage = ref(0), artifactCursorStack = ref([null]), artifactSort = ref(null), artifactLoading = ref(false)
const nodeReplays = ref([]), replayVisible = ref(false), replayDetail = ref(null), replayBusy = ref(null)
const activeReplayId = ref(null)
const canceling = ref(false)
const trialInputText = ref(JSON.stringify({ records: [{ orderId: 'P-1001', supplier: '甲供应商', amount: 128000, prediction: 'DUPLICATE_PAYMENT', predictionProbability: 0.92 }] }, null, 2))
let pollTimer = null, replayPollTimer = null, nodeCounter = 0
let loadingSnapshot = false

const filteredCatalog = computed(() => catalog.value.filter(item => (!typeFilter.value || item.operatorType === typeFilter.value) && (!catalogSearch.value.trim() || `${item.name} ${item.code}`.toLowerCase().includes(catalogSearch.value.trim().toLowerCase()))))
const selectedFlowName = computed(() => flows.value.find(flow => Number(flow.id) === Number(flowId.value))?.name || '')
const catalogGroups = computed(() => ['DATA','RULE','ML','AGENT','OUTPUT'].map(type => ({ type, items: filteredCatalog.value.filter(item => item.operatorType === type) })).filter(group => group.items.length))
const edgeContractsByKey = computed(() => Object.fromEntries((validation.value?.edgeContracts||[]).map(item=>[edgeKey(item),item])))
const selectedEdgeContract = computed(() => selectedEdge.value ? edgeContractsByKey.value[edgeKey(selectedEdge.value)] : null)
const sourceFields = computed(() => schemaFields(nodes.value.find(node=>node.id===selectedEdge.value?.source)?.outputSchema))
const targetFields = computed(() => schemaFields(nodes.value.find(node=>node.id===selectedEdge.value?.target)?.inputSchema))
function parseJson(value, fallback) { if (value == null || value === '') return fallback; if (typeof value === 'object') return value; try { return JSON.parse(value) } catch { return fallback } }
function typeIcon(type) { return ({ DATA:'⇥', RULE:'◇', ML:'✦', AGENT:'◎', OUTPUT:'▦' }[type] || '□') }
function typeLabel(type) { return ({ DATA:'数据加工算子', RULE:'规则算子', ML:'机器学习算子', AGENT:'智能体算子', OUTPUT:'输出算子' }[type] || type) }
function catalogItem(id) { return catalog.value.find(item => Number(item.operatorVersionId) === Number(id)) }
function startCatalogDrag(event, item) { event.dataTransfer.setData('application/x-smart-query-operator', JSON.stringify({ operatorVersionId:item.operatorVersionId })); event.dataTransfer.effectAllowed='copy' }
function quickAdd(item) { addOperator({ operatorVersionId:item.operatorVersionId }, { x: 60 + (nodes.value.length%3)*275, y: 60 + Math.floor(nodes.value.length/3)*145 }) }
function addOperator(payload, position) { const item = catalogItem(payload.operatorVersionId); if (!item) return; const id = `${item.code}_${++nodeCounter}`; const node = { id, name:item.name, operatorName:item.name, operatorVersionId:item.operatorVersionId, operatorType:item.operatorType, implementationType:item.implementationType, versionNo:item.versionNo, runtimeProfileCode:item.runtimeProfileCode, inputSchema:parseJson(item.inputSchema,{}), outputSchema:parseJson(item.outputSchema,{}), metadata:item.metadata||{}, position, config:{} }; nodes.value.push(node); selectNode(node); validation.value=null }
function hydrateNode(raw, index) { const item=catalogItem(raw.operatorVersionId); return { ...raw, id:String(raw.id), name:raw.name||item?.name||`节点 ${index+1}`, operatorName:item?.name||raw.name||'系统算子', operatorType:item?.operatorType||'OUTPUT', implementationType:item?.implementationType||raw.implementationType, versionNo:item?.versionNo||'?', runtimeProfileCode:item?.runtimeProfileCode||'locked-runtime', inputSchema:parseJson(item?.inputSchema,raw.inputSchema||{}), outputSchema:parseJson(item?.outputSchema,raw.outputSchema||{}), metadata:item?.metadata||{}, position:raw.position||{x:60+(index%3)*275,y:60+Math.floor(index/3)*145}, config:raw.config||{} } }
function hydrateEdge(raw) { return { ...raw, source:String(raw.source), target:String(raw.target), mappingMode:String(raw.mappingMode||'MERGE').toUpperCase(), fieldMappings:Array.isArray(raw.fieldMappings)?raw.fieldMappings.map(item=>({...item,required:item.required===true||item.required==='true',defaultValueText:Object.prototype.hasOwnProperty.call(item,'defaultValue')?JSON.stringify(item.defaultValue):''})):[] } }
function selectNode(node) { selectedNode.value=node; selectedEdge.value=null; nodeConfigText.value=JSON.stringify(node?.config||{},null,2) }
function selectEdge(edge) { selectedEdge.value=edge; selectedNode.value=null }
function moveNode(id, position) { const node=nodes.value.find(item=>item.id===id); if(node) node.position=position }
function beginConnection(id) { connectionSource.value=id; selectedEdge.value=null }
function completeConnection(target) { const source=connectionSource.value; if(!source) return; const sourceNode=nodes.value.find(item=>item.id===source), targetNode=nodes.value.find(item=>item.id===target); if(source===target){ElMessage.warning('节点不能连接自身')} else if(sourceNode?.operatorType==='OUTPUT'){ElMessage.warning('输出算子必须是终点')} else if(targetNode?.implementationType==='SQL_AST'){ElMessage.warning('SQL 数据入口必须是 DAG 根节点，不能接收上游连线')} else if(edges.value.some(edge=>edge.source===source&&edge.target===target)){ElMessage.info('该连线已存在')} else { const candidate={source,target,mappingMode:'MERGE',fieldMappings:[]}; if(wouldCycle(candidate)) ElMessage.error('该连线会形成环'); else edges.value.push(candidate) } connectionSource.value=null; validation.value=null }
function wouldCycle(extra) { const outgoing={}; nodes.value.forEach(node=>outgoing[node.id]=[]); [...edges.value,extra].forEach(edge=>outgoing[edge.source]?.push(edge.target)); const seen=new Set(), active=new Set(); const visit=id=>{if(active.has(id))return true;if(seen.has(id))return false;seen.add(id);active.add(id);for(const next of outgoing[id]||[])if(visit(next))return true;active.delete(id);return false};return nodes.value.some(node=>visit(node.id)) }
function removeNode(id) { nodes.value=nodes.value.filter(item=>item.id!==id); edges.value=edges.value.filter(edge=>edge.source!==id&&edge.target!==id); selectedNode.value=null; validation.value=null }
function removeSelectedEdge() { if(!selectedEdge.value)return; const key=`${selectedEdge.value.source}->${selectedEdge.value.target}`; edges.value=edges.value.filter(edge=>`${edge.source}->${edge.target}`!==key); selectedEdge.value=null; validation.value=null }
function addFieldMapping() { if(!selectedEdge.value)return; selectedEdge.value.fieldMappings.push({from:'',to:'',required:true,defaultValueText:''}); validation.value=null }
function removeFieldMapping(index) { selectedEdge.value?.fieldMappings.splice(index,1); validation.value=null }
function commitNodeConfig() { if(!selectedNode.value)return true; try{const value=JSON.parse(nodeConfigText.value||'{}');if(!value||Array.isArray(value)||typeof value!=='object')throw new Error();selectedNode.value.config=value;return true}catch{ElMessage.error('节点配置必须是 JSON 对象');return false} }
function nodeName(id) { return nodes.value.find(node=>node.id===id)?.name||id }
function edgeKey(edge) { return `${edge.sourceNodeId||edge.source}->${edge.targetNodeId||edge.target}` }
function schemaFields(rawSchema) {
  let schema=parseJson(rawSchema,{})||{}
  if(schema.type==='array'&&schema.items)schema=schema.items
  const rootProperties=schema.properties||{}
  if(rootProperties.records){const records=rootProperties.records;schema=records.items||records}
  else if(!Object.keys(rootProperties).length&&(schema.required||[]).includes('records'))return []
  const result=[]
  const walk=(current,prefix='',depth=0)=>{if(!current||depth>12)return;const properties=current.properties||{},required=new Set(current.required||[]);if(!Object.keys(properties).length){required.forEach(name=>result.push({path:prefix?`${prefix}.${name}`:name,type:'',required:true}));return}Object.entries(properties).forEach(([name,child])=>{const path=prefix?`${prefix}.${name}`:name;result.push({path,type:Array.isArray(child?.type)?child.type.join('|'):(child?.type||''),required:required.has(name)});walk(child,path,depth+1)})}
  walk(schema)
  return result
}

async function loadBase() { catalogLoading.value=true; try { [catalog.value,flows.value]=await Promise.all([fetchPublishedOperatorCatalog(),fetchFlows()]); if(!flowId.value&&flows.value.length){flowId.value=flows.value[0].id;await loadVersions()} await nextTick(); injectInitialOperator() } finally { catalogLoading.value=false } }
function injectInitialOperator() { if(!props.initialOperatorVersionId||nodes.value.some(node=>Number(node.operatorVersionId)===Number(props.initialOperatorVersionId)))return; const item=catalogItem(props.initialOperatorVersionId); if(item) quickAdd(item) }
async function loadVersions() { versions.value=flowId.value?await fetchFlowVersions(flowId.value):[]; selectedVersionId.value=versions.value[0]?.id||null; if(selectedVersionId.value)loadSelectedVersion();else newDraft(false) }
function loadSelectedVersion() { const version=versions.value.find(item=>item.id===selectedVersionId.value); if(!version){newDraft(false);return} resetRunView();loadingSnapshot=true;nodes.value=parseJson(version.nodes,[]).map(hydrateNode); edges.value=parseJson(version.edges,[]).map(hydrateEdge); selectedNode.value=null;selectedEdge.value=null;validation.value=parseJson(version.validationReport,null);nextTick(()=>{loadingSnapshot=false}) }
function newDraft(confirm=true) { const clear=()=>{resetRunView();selectedVersionId.value=null;nodes.value=[];edges.value=[];selectedNode.value=null;selectedEdge.value=null;validation.value=null;injectInitialOperator()}; if(confirm&&nodes.value.length)ElMessageBox.confirm('清空当前画布并创建新草稿？','新草稿',{type:'warning'}).then(clear).catch(()=>{});else clear() }
async function createNewFlow() { if(!newFlow.name.trim()||!/^[a-z][a-z0-9_-]{2,99}$/.test(newFlow.code)){ElMessage.warning('请填写名称和合法的小写编码');return} busy.value='create';try{const created=await createFlow(newFlow);flows.value.unshift(created);flowId.value=created.id;versions.value=[];newDraft(false);createVisible.value=false;Object.assign(newFlow,{name:'',code:'',description:''});ElMessage.success('V2 流程已创建')}finally{busy.value=''} }
function serializedNodes() { return nodes.value.map(({operatorName,operatorType,implementationType,versionNo,runtimeProfileCode,inputSchema,outputSchema,metadata,...node})=>node) }
function serializeMapping(mapping) { const {defaultValueText,...result}=mapping;if(defaultValueText!=null&&String(defaultValueText).trim()!=='')result.defaultValue=JSON.parse(defaultValueText);else delete result.defaultValue;return result }
function serializedEdges() { return edges.value.map(edge=>({source:edge.source,target:edge.target,mappingMode:String(edge.mappingMode||'MERGE').toUpperCase(),fieldMappings:(edge.fieldMappings||[]).map(serializeMapping)})) }
async function validateCurrent() { if(!commitNodeConfig())return false;let safeEdges;try{safeEdges=serializedEdges()}catch{ElMessage.error('边映射默认值必须是合法 JSON，例如 0、null、"未知" 或 {}');return false}busy.value='validate';try{validation.value=await validateDag(serializedNodes(),safeEdges);if(validation.value.valid)ElMessage.success('DAG 结构与边数据契约校验通过');else ElMessage.error(validation.value.errors?.[0]||'DAG 校验失败');return validation.value.valid}finally{busy.value=''} }
async function saveVersion() { if(!await validateCurrent())return;busy.value='save';try{const version=await createFlowVersion(flowId.value,{nodes:serializedNodes(),edges:serializedEdges(),parameterMappings:{}});await submitModelVersionApproval(flowId.value,version.id,'流程模型结构校验通过，提交不可变版本审批');versions.value=await fetchFlowVersions(flowId.value);selectedVersionId.value=version.id;loadSelectedVersion();ElMessage.success(`模型 v${version.versionNo} 已固化并提交版本审批`)}finally{busy.value=''} }

async function startTrial() { let input;try{input=JSON.parse(trialInputText.value)}catch{ElMessage.error('试运行输入不是有效 JSON');return} busy.value='trial';runOutputs.value=[];artifactView.value=null;resetArtifactQuery();runNodes.value=[];nodeStatuses.value={};nodeReplays.value=[];replayDetail.value=null;try{currentRun.value=await submitTrialRun(selectedVersionId.value,input);await pollRun()}finally{busy.value=''} }
async function pollRun() { clearPoll(); const tick=async()=>{if(!currentRun.value)return;currentRun.value=await fetchRun(currentRun.value.id);runNodes.value=await fetchRunNodes(currentRun.value.id);nodeStatuses.value=Object.fromEntries(runNodes.value.map(item=>[item.nodeId,item.status]));if(['SUCCESS','FAILED','CANCELED'].includes(currentRun.value.status)){nodeReplays.value=await fetchNodeReplays(currentRun.value.id);if(currentRun.value.status==='SUCCESS'){runOutputs.value=await fetchRunOutputs(currentRun.value.id);selectedArtifactId.value=runOutputs.value[0]?.id||null;resetArtifactQuery();if(selectedArtifactId.value)await loadArtifact();ElMessage.success('DAG 试运行成功')}else if(currentRun.value.status==='CANCELED')ElMessage.warning('DAG 试运行已取消');else ElMessage.error(currentRun.value.errorMessage||'DAG 试运行失败');busy.value='';return}pollTimer=setTimeout(tick,900)};await tick() }
async function cancelCurrentRun() { if(!currentRun.value)return;canceling.value=true;try{currentRun.value=await cancelRun(currentRun.value.id);clearPoll();busy.value='';runNodes.value=await fetchRunNodes(currentRun.value.id);nodeStatuses.value=Object.fromEntries(runNodes.value.map(item=>[item.nodeId,item.status]));nodeReplays.value=await fetchNodeReplays(currentRun.value.id);if(currentRun.value.status==='CANCELED')ElMessage.success('运行已取消，未提交任何线索或可视化结果');else ElMessage.info(`运行已经进入 ${currentRun.value.status} 状态`)}finally{canceling.value=false} }
function nodeRunFor(nodeId){return runNodes.value.find(item=>item.nodeId===nodeId)}
function canReplay(nodeId){const item=nodeRunFor(nodeId);return item&&['SUCCESS','FAILED','TIMED_OUT'].includes(item.status)&&['SUCCESS','FAILED','CANCELED'].includes(currentRun.value?.status)}
function latestReplay(nodeId){return nodeReplays.value.find(item=>item.nodeId===nodeId)}
async function startNodeReplay(nodeId){const nodeRun=nodeRunFor(nodeId);if(!nodeRun||!currentRun.value)return;replayBusy.value=nodeId;try{const created=await createNodeReplay(currentRun.value.id,nodeRun.id);nodeReplays.value=[created,...nodeReplays.value];replayVisible.value=true;await openReplay(created.id)}finally{replayBusy.value=null}}
async function openReplay(replayId){replayVisible.value=true;replayDetail.value=null;clearReplayPoll();activeReplayId.value=replayId;const tick=async()=>{if(activeReplayId.value!==replayId)return;replayDetail.value=await fetchNodeReplay(replayId);if(activeReplayId.value!==replayId)return;const updated=replayDetail.value.replay;nodeReplays.value=[updated,...nodeReplays.value.filter(item=>item.id!==updated.id)];if(['SUCCESS','FAILED','CANCELED','TIMED_OUT'].includes(updated.status)){if(updated.status==='SUCCESS')ElMessage.success(updated.outputHash===updated.expectedOutputHash?'回放完成：输出完全一致':'回放完成：检测到输出漂移');return}replayPollTimer=setTimeout(tick,900)};await tick()}
async function cancelReplay(){if(!replayDetail.value)return;await cancelNodeReplay(replayDetail.value.replay.id);clearReplayPoll();replayDetail.value=await fetchNodeReplay(replayDetail.value.replay.id);const updated=replayDetail.value.replay;nodeReplays.value=[updated,...nodeReplays.value.filter(item=>item.id!==updated.id)];ElMessage.success('节点回放已取消')}
function resetArtifactQuery(){artifactPage.value=0;artifactCursorStack.value=[null];artifactSort.value=null}
async function loadArtifact(){if(!selectedArtifactId.value){artifactView.value=null;return}artifactLoading.value=true;try{artifactView.value=await queryOutputView(selectedArtifactId.value,{pageSize:50,cursor:artifactCursorStack.value[artifactPage.value],filters:[],sort:artifactSort.value})}finally{artifactLoading.value=false}}
async function changeArtifact(){resetArtifactQuery();await loadArtifact()}
async function changeArtifactSort(event){artifactSort.value=event.order?{field:event.prop,direction:event.order==='descending'?'DESC':'ASC'}:null;artifactPage.value=0;artifactCursorStack.value=[null];await loadArtifact()}
async function nextArtifactPage(){if(!artifactView.value?.nextCursor)return;artifactCursorStack.value=[...artifactCursorStack.value.slice(0,artifactPage.value+1),artifactView.value.nextCursor];artifactPage.value++;await loadArtifact()}
async function previousArtifactPage(){if(artifactPage.value===0)return;artifactPage.value--;await loadArtifact()}
function clearPoll(){if(pollTimer){clearTimeout(pollTimer);pollTimer=null}}
function clearReplayPoll(){activeReplayId.value=null;if(replayPollTimer){clearTimeout(replayPollTimer);replayPollTimer=null}}
function resetRunView(){clearPoll();clearReplayPoll();currentRun.value=null;runNodes.value=[];nodeStatuses.value={};runOutputs.value=[];selectedArtifactId.value=null;artifactView.value=null;resetArtifactQuery();nodeReplays.value=[];replayDetail.value=null}
function runStatusType(status){return({SUCCESS:'success',FAILED:'danger',TIMED_OUT:'danger',CANCELED:'info',RUNNING:'warning',COMMITTING:'warning',QUEUED:'info',PENDING:'info'}[status]||'info')}
function shortHash(value){return value?`${String(value).slice(0,12)}…`:''}
function formatJson(value){return JSON.stringify(value??null,null,2)}
watch(()=>props.initialOperatorVersionId,injectInitialOperator)
watch([nodes,edges],()=>{if(!loadingSnapshot){validation.value=null;if(selectedVersionId.value)selectedVersionId.value=null}},{deep:true})
onMounted(loadBase);onBeforeUnmount(()=>{clearPoll();clearReplayPoll()})
</script>

<style scoped>
.dag-designer{flex:1;min-width:0;background:var(--bg);display:flex;flex-direction:column}.dag-header{gap:10px}.spacer{flex:1}.flow-select{width:190px}.version-select{width:190px}.designer-layout{flex:1;min-height:0;display:grid;grid-template-columns:265px minmax(620px,1fr) 390px;border-top:1px solid var(--border-light)}.operator-palette,.inspector{min-height:0;background:var(--surface);padding:14px;overflow:auto}.operator-palette{border-right:1px solid var(--border-light)}.inspector{border-left:1px solid var(--border-light)}.panel-title{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px}.type-filter{margin:10px 0;width:100%;display:grid;grid-template-columns:repeat(3,1fr)}.type-filter :deep(.el-radio-button__inner){padding:6px 8px;width:100%}.palette-list{min-height:200px}.palette-group{margin-bottom:15px}.palette-group-title{display:flex;justify-content:space-between;color:var(--text-muted);font-size:11px;font-weight:700;margin:7px 2px}.palette-item{width:100%;display:flex;align-items:center;gap:9px;margin-bottom:6px;padding:9px;border:1px solid var(--border-light);border-left:4px solid #64748b;border-radius:8px;background:white;text-align:left;cursor:grab}.palette-item:hover{border-color:var(--brand-primary);background:var(--brand-primary-lighter)}.palette-item.type-rule{border-left-color:#7c3aed}.palette-item.type-ml{border-left-color:#db2777}.palette-item.type-data{border-left-color:#0ea5e9}.palette-item.type-agent{border-left-color:#ea580c}.palette-item.type-output{border-left-color:#059669}.palette-icon{font-size:18px}.palette-main{min-width:0;flex:1;display:flex;flex-direction:column}.palette-main strong,.palette-main small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.palette-main strong{font-size:13px}.palette-main small{color:var(--text-muted);font-size:10px;margin-top:3px}.canvas-shell{min-width:0;min-height:0;display:flex;flex-direction:column;overflow:hidden}.canvas-toolbar{height:38px;display:flex;align-items:center;gap:12px;padding:0 12px;background:white;border-bottom:1px solid var(--border-light);font-size:12px;color:var(--text-muted)}.locked-note{flex:1;color:#175cd3}.canvas-shell :deep(.dag-canvas){flex:1}.capability-panel{display:flex;flex-direction:column;gap:7px;margin:0 0 15px;padding:10px;border:1px solid var(--border-light);border-radius:8px}.capability-panel span,.capability-panel small{font-size:11px;line-height:1.5;color:var(--text-muted)}.capability-panel>div{display:flex;flex-wrap:wrap;gap:5px}.sql-policy{background:#f0f9ff;border-color:#bae6fd}.agent-policy{background:#fff7ed;border-color:#fed7aa}.field-note{margin-left:8px;color:var(--text-muted);font-size:11px}.json-editor :deep(textarea){font-family:ui-monospace,Consolas,monospace;font-size:12px}.edge-detail{display:flex;align-items:center;justify-content:center;gap:12px;padding:14px 8px;margin-bottom:12px;border-radius:8px;background:var(--bg)}.edge-mode-note{display:block;margin:7px 0 0}.schema-summary{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:15px}.schema-summary>div{display:flex;align-content:flex-start;gap:4px;flex-wrap:wrap;min-height:72px;padding:8px;border:1px solid var(--border-light);border-radius:7px;background:#f8fafc}.schema-summary strong,.schema-summary>div>span{width:100%;font-size:11px}.schema-summary>div>span{color:var(--text-muted)}.mapping-item{width:100%;margin-bottom:8px;padding:7px;border:1px solid var(--border-light);border-radius:7px;background:#f8fafc}.mapping-row{width:100%;display:grid;grid-template-columns:minmax(90px,1fr) 16px minmax(90px,1fr) 24px;align-items:center;gap:4px}.mapping-options{display:grid;grid-template-columns:auto 1fr;align-items:center;gap:8px;margin-top:6px}.mapping-arrow{text-align:center;color:var(--text-muted)}.add-mapping{width:100%;margin-top:3px}.edge-actions{display:flex;justify-content:space-between;margin-top:12px}.edge-form :deep(.el-form-item__content){display:block}.edge-form :deep(.el-radio-group){width:100%}.edge-form :deep(.el-radio-button){flex:1}.edge-form :deep(.el-radio-button__inner){width:100%}.inspector :deep(.el-alert__content p){margin:4px 0;font-size:11px;line-height:1.45}.validation-box{margin-top:20px;padding-top:15px;border-top:1px solid var(--border-light)}.validation-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:9px}.error-line,.warning-line,.levels,.muted{font-size:12px;line-height:1.5}.error-line{color:#b42318}.warning-line{color:#b54708}.levels{color:#027a48;word-break:break-word}.muted{color:var(--text-muted)}.trial-layout{display:grid;grid-template-columns:1.15fr 1fr;gap:16px}.trial-input{display:flex;flex-direction:column;gap:10px}.trial-status{max-height:440px;overflow:auto}.run-summary,.run-node-row{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:9px;border-bottom:1px solid var(--border-lighter)}.run-summary>span:first-child,.run-node-name,.replay-head>span:first-child{display:flex;flex-direction:column;gap:3px}.run-summary small,.run-node-name small,.replay-head small{font-size:10px;color:var(--text-muted)}.run-actions{display:flex;align-items:center;gap:7px}.run-output-tabs{height:520px;margin-top:18px;padding-top:14px;border-top:1px solid var(--border-light);display:flex;flex-direction:column;gap:10px}.run-output-toolbar{display:flex;align-items:center;justify-content:flex-end;gap:8px;color:var(--text-muted);font-size:12px}.run-output-toolbar>.el-select{margin-right:auto}.run-output-tabs :deep(.artifact-viewer){flex:1;min-height:0}.replay-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px}.replay-binding{margin:14px 0}.diff-cards{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:14px 0}.diff-card{display:flex;flex-direction:column;gap:4px;padding:13px;border:1px solid var(--border-light);border-radius:8px;background:#f8fafc}.diff-card strong{font-size:18px}.diff-card small{color:var(--text-muted)}.diff-card.exact{background:#ecfdf3;border-color:#abefc6;color:#067647}.diff-card.drift{background:#fffaeb;border-color:#fedf89;color:#b54708}.replay-tabs{margin-top:12px}.compare-json{display:grid;grid-template-columns:1fr 1fr;gap:12px;padding:10px}.compare-json section{min-width:0}.compare-json pre,.sample-json{max-height:340px;margin:8px 0 0;padding:12px;overflow:auto;border-radius:8px;background:#111827;color:#d1fae5;font:12px/1.55 ui-monospace,Consolas,monospace;white-space:pre-wrap;word-break:break-word}@media(max-width:1350px){.designer-layout{grid-template-columns:230px minmax(590px,1fr) 350px}.mapping-row{grid-template-columns:minmax(80px,1fr) 14px minmax(80px,1fr) 22px}}
.dag-designer.embedded{max-width:none;margin:0;padding:0}.embedded .dag-header{min-height:64px;margin:0;padding:10px 16px;background:white}.dag-heading{display:flex;flex-direction:column;gap:2px;flex-shrink:0}.dag-heading>span{color:var(--text-muted);font-size:10px}.palette-note{display:flex;align-items:center;justify-content:space-between;margin:-4px 0 10px;color:var(--text-muted);font-size:11px}
</style>
