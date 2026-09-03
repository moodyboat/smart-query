<template>
  <div class="operator-library-center">
    <template v-if="!studioType">
      <div class="library-toolbar">
        <div>
          <h2>统一算子库</h2>
          <p>五类算子共用一个目录、版本治理规则和 DAG 入口；画布仅使用已发布的不可变版本。</p>
        </div>
        <div class="toolbar-actions">
          <el-button :loading="loading" @click="load">刷新</el-button>
          <el-button type="primary" @click="typeDialog = true">+ 创建算子</el-button>
        </div>
      </div>

      <div class="library-stats">
        <div><strong>{{ operators.length }}</strong><span>算子定义</span></div>
        <div><strong>{{ publishedDefinitionCount }}</strong><span>已发布</span></div>
        <div><strong>{{ typeCount }}</strong><span>已覆盖类型</span></div>
        <div><strong>5</strong><span>统一类型</span></div>
      </div>

      <div class="library-filters">
        <el-input v-model="search" clearable placeholder="搜索名称、编码或说明" class="search-input" />
      </div>

      <el-tabs v-model="typeFilter" class="operator-type-tabs">
        <el-tab-pane v-for="type in typeTabs" :key="type.value || 'all'" :name="type.value">
          <template #label>
            <span class="type-tab-label">
              <span v-if="type.value" :class="['tab-dot', `is-${type.value.toLowerCase()}`]"></span>
              {{ type.label }}
              <em>{{ operatorCount(type.value) }}</em>
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <div class="operator-grid" v-loading="loading">
        <article v-for="item in filteredOperators" :key="item.id" class="operator-card">
          <div class="operator-card-head">
            <span :class="['type-symbol', `is-${item.operatorType.toLowerCase()}`]">{{ typeMeta(item.operatorType).short }}</span>
            <div class="operator-title"><strong>{{ item.name }}</strong><code>{{ item.code }}</code></div>
            <el-tag :type="latestPublished(item.id) ? 'success' : 'info'" effect="plain" size="small">
              {{ latestPublished(item.id) ? `已发布 v${latestPublished(item.id).versionNo}` : '尚未发布' }}
            </el-tag>
          </div>
          <p>{{ item.description || typeMeta(item.operatorType).description }}</p>
          <div class="operator-meta">
            <span>{{ typeMeta(item.operatorType).label }}</span>
            <span v-if="latestPublished(item.id)">{{ latestPublished(item.id).runtimeProfileCode }}</span>
            <span v-else>等待构建版本</span>
          </div>
          <div class="operator-actions">
            <el-tag v-if="item.ownerUserId === 'SYSTEM'" size="small" effect="plain" type="info">平台托管</el-tag>
            <el-button v-else link type="primary" @click="openStudio(item.operatorType, false)">进入构建器</el-button>
            <el-button v-if="latestPublished(item.id)" link type="success" @click="emit('openDag', latestPublished(item.id).operatorVersionId)">加入流程</el-button>
          </div>
        </article>
        <el-empty v-if="!loading && !filteredOperators.length" description="暂无匹配算子；可以从右上角创建第一项" />
      </div>
    </template>

    <template v-else>
      <div class="studio-header">
        <button type="button" class="studio-back" @click="closeStudio">← 返回算子库</button>
        <span :class="['type-symbol', `is-${studioType.toLowerCase()}`]">{{ typeMeta(studioType).short }}</span>
        <div><h2>{{ typeMeta(studioType).label }}构建器</h2><p>{{ typeMeta(studioType).workflow }}</p></div>
        <el-tag effect="plain">{{ studioType }}</el-tag>
      </div>
      <div class="studio-body">
        <PolicyAuthoringWorkbench v-if="studioType === 'DATA'" :conversation-id="props.conversationId"
          initial-operator-type="DATA" lock-operator-type :auto-create="studioAutoCreate" @openDag="emit('openDag', $event)" />
        <RuleAuthoringWorkbench v-else-if="studioType === 'RULE'" :conversation-id="props.conversationId"
          :auto-create="studioAutoCreate" @openDag="emit('openDag', $event)" />
        <MiningManager v-else-if="studioType === 'ML'" embedded :auto-create="studioAutoCreate" />
        <PolicyAuthoringWorkbench v-else-if="studioType === 'AGENT'" :conversation-id="props.conversationId"
          initial-operator-type="AGENT" lock-operator-type :auto-create="studioAutoCreate" @openDag="emit('openDag', $event)" />
        <OutputAuthoringWorkbench v-else :conversation-id="props.conversationId"
          :auto-create="studioAutoCreate" @openDag="emit('openDag', $event)" />
      </div>
    </template>

    <el-dialog v-model="typeDialog" title="选择算子类型" width="820px" destroy-on-close>
      <p class="dialog-intro">先选择能力类型，系统会进入对应构建页面，并应用该类型的运行时、测试和发布门禁。</p>
      <div class="type-choice-grid">
        <button v-for="type in operatorTypes" :key="type.value" type="button" class="type-choice" @click="openStudio(type.value, true)">
          <span :class="['type-symbol', `is-${type.value.toLowerCase()}`]">{{ type.short }}</span>
          <span class="type-choice-copy"><strong>{{ type.label }}</strong><small>{{ type.description }}</small><em>{{ type.workflow }}</em></span>
          <span class="choice-arrow">→</span>
        </button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import MiningManager from './MiningManager.vue'
import OutputAuthoringWorkbench from './OutputAuthoringWorkbench.vue'
import PolicyAuthoringWorkbench from './PolicyAuthoringWorkbench.vue'
import RuleAuthoringWorkbench from './RuleAuthoringWorkbench.vue'
import { fetchOperators, fetchPublishedOperatorCatalog } from '../api/orchestration.js'

const props = defineProps({ conversationId: { type: Number, default: null } })
const emit = defineEmits(['openDag'])
const operators = ref([])
const catalog = ref([])
const loading = ref(false)
const search = ref('')
const typeFilter = ref('')
const typeDialog = ref(false)
const studioType = ref('')
const studioAutoCreate = ref(false)

const operatorTypes = [
  { value: 'DATA', short: 'D', label: '数据加工算子', description: '受控读取、关联、过滤、聚合与字段加工。', workflow: '固定数据范围 → 对话生成策略 → 真实预览 → 审批发布' },
  { value: 'RULE', short: 'R', label: '规则算子', description: '按业务语言创造新的 Python 规则能力。', workflow: '对话生成源码 → 隔离沙箱测试 → 审批发布' },
  { value: 'ML', short: 'ML', label: '机器学习算子', description: '绑定已训练模型，输出预测及对应原始输入。', workflow: '模型训练发布 → 固定制品与运行时 → 审批发布' },
  { value: 'AGENT', short: 'A', label: '智能体算子', description: '在模型、工具白名单和预算内完成研判。', workflow: '固定权限范围 → 策略整形 → 真实预览 → 审批发布' },
  { value: 'OUTPUT', short: 'O', label: '输出算子', description: '将线索、图表或类 Excel 结果声明式可视化。', workflow: '对话生成草稿 → 沙箱整形 → 预览验证 → 审批发布' }
]
const typeTabs = [{ label: '全部算子', value: '' }, ...operatorTypes.map(item => ({ label: item.label, value: item.value }))]
const filteredOperators = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return operators.value.filter(item => (!typeFilter.value || item.operatorType === typeFilter.value)
    && (!keyword || `${item.name} ${item.code} ${item.description || ''}`.toLowerCase().includes(keyword)))
})
const publishedDefinitionCount = computed(() => new Set(catalog.value.map(item => item.operatorId)).size)
const typeCount = computed(() => new Set(operators.value.map(item => item.operatorType)).size)

function typeMeta(type) { return operatorTypes.find(item => item.value === type) || { short: '?', label: type, description: '', workflow: '' } }
function operatorCount(type) { return type ? operators.value.filter(item => item.operatorType === type).length : operators.value.length }
function latestPublished(operatorId) { return catalog.value.find(item => Number(item.operatorId) === Number(operatorId)) }
function openStudio(type, autoCreate) { studioType.value = type; studioAutoCreate.value = autoCreate; typeDialog.value = false }
function openCreate() { studioType.value = ''; studioAutoCreate.value = false; typeDialog.value = true }
async function closeStudio() { studioType.value = ''; studioAutoCreate.value = false; await load() }
async function load() {
  loading.value = true
  try { [operators.value, catalog.value] = await Promise.all([fetchOperators(), fetchPublishedOperatorCatalog()]) }
  finally { loading.value = false }
}
onMounted(load)
defineExpose({ openCreate, refresh: load })
</script>

<style scoped>
.operator-library-center { height: 100%; min-height: 0; display: flex; flex-direction: column; background: #f6f8fc; }
.library-toolbar { display: flex; align-items: flex-start; gap: 18px; padding: 22px 26px 16px; }
.library-toolbar > div:first-child { flex: 1; }
.library-toolbar h2, .studio-header h2 { margin: 0 0 5px; color: #102a4c; font-size: 20px; }
.library-toolbar p, .studio-header p, .dialog-intro { margin: 0; color: #6a7890; font-size: 13px; line-height: 1.55; }
.toolbar-actions { display: flex; gap: 8px; }
.library-stats { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 10px; padding: 0 26px 16px; }
.library-stats > div { display: flex; align-items: baseline; gap: 8px; padding: 13px 16px; border: 1px solid #e2e9f3; border-radius: 10px; background: white; }
.library-stats strong { color: #1559b7; font-size: 22px; }
.library-stats span { color: #758197; font-size: 12px; }
.library-filters { display: flex; align-items: center; gap: 12px; padding: 14px 26px 10px; border-top: 1px solid #e6ebf2; background: white; }
.search-input { width: 300px; }
.operator-type-tabs { padding: 0 26px; border-bottom: 1px solid #e6ebf2; background: white; }
.operator-type-tabs :deep(.el-tabs__header) { margin: 0; }
.operator-type-tabs :deep(.el-tabs__content) { display: none; }
.type-tab-label { display: inline-flex; align-items: center; gap: 7px; }
.type-tab-label em { min-width: 18px; padding: 1px 5px; border-radius: 9px; background: #edf2f8; color: #718096; font-size: 9px; font-style: normal; line-height: 16px; text-align: center; }
.tab-dot { width: 7px; height: 7px; border-radius: 50%; background: #3b82f6; }
.tab-dot.is-rule { background: #7c3aed; }.tab-dot.is-ml { background: #db2777; }.tab-dot.is-agent { background: #ea580c; }.tab-dot.is-output { background: #059669; }
.operator-grid { flex: 1; min-height: 0; overflow: auto; display: grid; grid-template-columns: repeat(auto-fill, minmax(315px, 1fr)); align-content: start; gap: 12px; padding: 18px 26px 28px; }
.operator-card { display: flex; flex-direction: column; min-height: 176px; padding: 16px; border: 1px solid #dfe7f1; border-radius: 11px; background: white; transition: border-color .15s, box-shadow .15s, transform .15s; }
.operator-card:hover { border-color: #9dc1f4; box-shadow: 0 7px 20px rgba(37, 99, 235, .08); transform: translateY(-1px); }
.operator-card-head { display: flex; align-items: center; gap: 10px; }
.operator-title { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 3px; }
.operator-title strong { overflow: hidden; color: #183153; text-overflow: ellipsis; white-space: nowrap; }
.operator-title code { overflow: hidden; color: #8793a5; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.operator-card > p { flex: 1; margin: 14px 0; color: #67758a; font-size: 12px; line-height: 1.55; }
.operator-meta, .operator-actions { display: flex; align-items: center; gap: 10px; }
.operator-meta { color: #7a879a; font-size: 11px; }
.operator-meta span + span::before { content: '·'; margin-right: 10px; }
.operator-actions { justify-content: flex-end; margin-top: 8px; border-top: 1px solid #edf1f6; padding-top: 8px; }
.type-symbol { width: 36px; height: 36px; display: inline-grid; place-items: center; flex-shrink: 0; border: 1px solid #bfdbfe; border-radius: 9px; background: #eff6ff; color: #1d4ed8; font-size: 12px; font-weight: 800; }
.type-symbol.is-rule { background: #f5f3ff; border-color: #ddd6fe; color: #6d28d9; }
.type-symbol.is-ml { background: #fdf2f8; border-color: #fbcfe8; color: #be185d; }
.type-symbol.is-agent { background: #fff7ed; border-color: #fed7aa; color: #c2410c; }
.type-symbol.is-output { background: #ecfdf5; border-color: #a7f3d0; color: #047857; }
.type-choice-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 16px; }
.type-choice:last-child { grid-column: 1 / -1; }
.type-choice { display: flex; align-items: center; gap: 12px; padding: 15px; border: 1px solid #dfe7f1; border-radius: 10px; background: white; text-align: left; cursor: pointer; transition: all .15s; }
.type-choice:hover { border-color: #8bb7ef; background: #f8fbff; box-shadow: 0 5px 16px rgba(37, 99, 235, .08); }
.type-choice-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 4px; }
.type-choice-copy strong { color: #17365d; }
.type-choice-copy small { color: #69778b; line-height: 1.4; }
.type-choice-copy em { color: #3675c9; font-size: 11px; font-style: normal; }
.choice-arrow { color: #2563eb; font-size: 18px; }
.studio-header { display: flex; align-items: center; gap: 12px; min-height: 74px; padding: 12px 22px; border-bottom: 1px solid #dfe7f1; background: white; }
.studio-header > div { min-width: 0; flex: 1; }
.studio-header h2 { font-size: 17px; }
.studio-back { align-self: stretch; padding: 0 12px 0 0; border: 0; border-right: 1px solid #e4e9f0; background: transparent; color: #2563eb; cursor: pointer; }
.studio-body { flex: 1; min-height: 0; display: flex; overflow: hidden; }
@media (max-width: 900px) { .library-stats { grid-template-columns: repeat(2, 1fr); } .library-filters { align-items: stretch; flex-direction: column; } .search-input { width: 100%; } .operator-type-tabs { overflow-x: auto; padding-inline: 14px; }.type-choice-grid { grid-template-columns: 1fr; } .type-choice:last-child { grid-column: auto; } }
</style>
