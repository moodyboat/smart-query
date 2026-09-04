<template>
  <div class="operator-library-center">
    <template v-if="!studioType">
      <div class="library-toolbar">
        <div class="toolbar-actions">
          <el-button :loading="loading" @click="load">刷新</el-button>
          <el-button @click="openAlgorithmGovernance">算法模板治理</el-button>
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
        <article v-for="item in filteredOperators" :key="item.id" :class="['operator-card', `is-${item.operatorType.toLowerCase()}`]">
          <div class="operator-card-head">
            <span :class="['type-symbol', `is-${item.operatorType.toLowerCase()}`]">{{ typeMeta(item.operatorType).short }}</span>
            <div class="operator-title">
              <strong :title="item.name">{{ item.name }}</strong>
              <span>{{ typeMeta(item.operatorType).label }}</span>
            </div>
            <el-tag :type="latestPublished(item.id) ? 'success' : 'info'" effect="plain" size="small">
              {{ latestPublished(item.id) ? '已发布' : '待构建' }}
            </el-tag>
          </div>
          <p class="operator-summary">{{ item.description || '按业务需要配置后，可加入流程重复使用。' }}</p>
          <div class="operator-facts">
            <div>
              <span>当前版本</span>
              <strong>{{ latestPublished(item.id) ? `版本 ${latestPublished(item.id).versionNo}` : '尚未发布' }}</strong>
            </div>
            <div>
              <span>运行环境</span>
              <strong>{{ latestPublished(item.id) ? '平台运行环境' : '发布后确定' }}</strong>
            </div>
          </div>
          <div class="operator-actions">
            <span class="operator-owner">{{ item.ownerUserId === 'SYSTEM' ? '平台托管' : '我的算子' }}</span>
            <el-button v-if="item.ownerUserId !== 'SYSTEM'" link @click="openStudio(item.operatorType, false)">管理</el-button>
            <el-button v-if="latestPublished(item.id)" link type="primary" @click="emit('openDag', latestPublished(item.id).operatorVersionId)">加入流程</el-button>
          </div>
        </article>
        <el-empty v-if="!loading && !filteredOperators.length" description="暂无匹配算子" />
      </div>
    </template>

    <template v-else>
      <div class="studio-header">
        <button type="button" class="studio-back" @click="closeStudio">← 返回算子库</button>
        <span :class="['type-symbol', `is-${studioType.toLowerCase()}`]">{{ typeMeta(studioType).short }}</span>
        <div><h2>{{ typeMeta(studioType).label }}构建器</h2></div>
      </div>
      <div class="studio-body">
        <PolicyAuthoringWorkbench v-if="studioType === 'DATA'" :conversation-id="props.conversationId"
          initial-operator-type="DATA" lock-operator-type :auto-create="studioAutoCreate" @openDag="emit('openDag', $event)" />
        <RuleAuthoringWorkbench v-else-if="studioType === 'RULE'" :conversation-id="props.conversationId"
          :auto-create="studioAutoCreate" @openDag="emit('openDag', $event)" />
        <MiningManager v-else-if="studioType === 'ML'" embedded :auto-create="studioAutoCreate" :initial-tab="studioInitialTab" />
        <PolicyAuthoringWorkbench v-else-if="studioType === 'AGENT'" :conversation-id="props.conversationId"
          initial-operator-type="AGENT" lock-operator-type :auto-create="studioAutoCreate" @openDag="emit('openDag', $event)" />
        <OutputAuthoringWorkbench v-else :conversation-id="props.conversationId"
          :auto-create="studioAutoCreate" @openDag="emit('openDag', $event)" />
      </div>
    </template>

    <el-dialog v-model="typeDialog" title="选择算子类型" width="820px" destroy-on-close>
      <div class="type-choice-grid">
        <button v-for="type in operatorTypes" :key="type.value" type="button" class="type-choice" @click="openStudio(type.value, true)">
          <span :class="['type-symbol', `is-${type.value.toLowerCase()}`]">{{ type.short }}</span>
          <span class="type-choice-copy"><strong>{{ type.label }}</strong></span>
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
const studioInitialTab = ref('')

const operatorTypes = [
  { value: 'DATA', short: '数', label: '数据加工算子' },
  { value: 'RULE', short: '规', label: '规则算子' },
  { value: 'ML', short: '学', label: '机器学习算子' },
  { value: 'AGENT', short: '智', label: '智能体算子' },
  { value: 'OUTPUT', short: '出', label: '输出算子' }
]
const typeTabs = [{ label: '全部算子', value: '' }, ...operatorTypes.map(item => ({ label: item.label, value: item.value }))]
const filteredOperators = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return operators.value.filter(item => (!typeFilter.value || item.operatorType === typeFilter.value)
    && (!keyword || `${item.name} ${item.code} ${item.description || ''}`.toLowerCase().includes(keyword)))
})
const publishedDefinitionCount = computed(() => new Set(catalog.value.map(item => item.operatorId)).size)
const typeCount = computed(() => new Set(operators.value.map(item => item.operatorType)).size)

function typeMeta(type) { return operatorTypes.find(item => item.value === type) || { short: '算', label: type } }
function operatorCount(type) { return type ? operators.value.filter(item => item.operatorType === type).length : operators.value.length }
function latestPublished(operatorId) { return catalog.value.find(item => Number(item.operatorId) === Number(operatorId)) }
function openStudio(type, autoCreate) { studioType.value = type; studioAutoCreate.value = autoCreate; studioInitialTab.value = ''; typeDialog.value = false }
function openAlgorithmGovernance() { studioType.value = 'ML'; studioAutoCreate.value = false; studioInitialTab.value = 'algorithms' }
function openCreate() { studioType.value = ''; studioAutoCreate.value = false; typeDialog.value = true }
async function closeStudio() { studioType.value = ''; studioAutoCreate.value = false; studioInitialTab.value = ''; await load() }
async function load() {
  loading.value = true
  try { [operators.value, catalog.value] = await Promise.all([fetchOperators(), fetchPublishedOperatorCatalog()]) }
  finally { loading.value = false }
}
onMounted(load)
defineExpose({ openCreate, refresh: load })
</script>

<style scoped>
.operator-library-center { width:100%;height:100%;min-width:0;min-height:0;flex:1;display:flex;flex-direction:column;background:#f5f7fa; }
.library-toolbar { display: flex; align-items: center; justify-content: flex-end; gap: 18px; padding: 14px 20px 8px; }
.library-toolbar > div:first-child { flex: 1; }
.library-toolbar h2, .studio-header h2 { margin: 0 0 5px; color: #1d1d1f; font-size: 19px; font-weight: 660; letter-spacing: -.03em; }
.library-toolbar p, .studio-header p, .dialog-intro { margin: 0; color: #7b7b80; font-size: 11px; line-height: 1.55; }
.toolbar-actions { display: flex; gap: 8px; }
.library-stats { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 10px; padding: 0 26px 16px; }
.library-stats > div { display:flex;align-items:baseline;gap:8px;padding:12px 13px;border:1px solid #e4e8ef;border-radius:9px;background:#fff;box-shadow:none; }
.library-stats strong { color: #2468f2; font-size: 22px; font-weight: 650; }
.library-stats span { color: #86868b; font-size: 10px; }
.library-filters { display:flex;align-items:center;gap:12px;padding:12px 26px 10px;border-top:1px solid #e4e8ef;background:#fff; }
.search-input { width: 300px; }
.operator-type-tabs { padding:0 26px;border-bottom:1px solid #e4e8ef;background:#fff; }
.operator-type-tabs :deep(.el-tabs__header) { margin: 0; }
.operator-type-tabs :deep(.el-tabs__content) { display: none; }
.type-tab-label { display: inline-flex; align-items: center; gap: 7px; }
.type-tab-label em { min-width: 18px; padding: 1px 5px; border-radius: 9px; background: #edf2f8; color: #718096; font-size: 9px; font-style: normal; line-height: 16px; text-align: center; }
.tab-dot { width: 7px; height: 7px; border-radius: 50%; background: #3b82f6; }
.tab-dot.is-rule { background: #7c3aed; }.tab-dot.is-ml { background: #db2777; }.tab-dot.is-agent { background: #ea580c; }.tab-dot.is-output { background: #059669; }
.operator-grid { flex:1;min-width:0;min-height:0;overflow:auto;display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));align-content:start;gap:12px;padding:18px 26px 28px; }
.operator-card { position:relative;display:flex;flex-direction:column;min-width:0;min-height:196px;padding:15px 15px 12px;overflow:hidden;border:1px solid #e2e7ee;border-radius:10px;background:#fff;box-shadow:none;transition:border-color .15s,background-color .15s; }
.operator-card::before { content:'';position:absolute;inset:0 auto 0 0;width:3px;background:#3b82f6; }
.operator-card.is-rule::before { background:#7c3aed; }.operator-card.is-ml::before { background:#db2777; }.operator-card.is-agent::before { background:#ea580c; }.operator-card.is-output::before { background:#059669; }
.operator-card:hover { border-color:#adc6ee;background:#fcfdff;box-shadow:none;transform:none; }
.operator-card-head { display: flex; align-items: center; gap: 10px; }
.operator-title { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 3px; }
.operator-title strong { overflow:hidden;color:#24262b;font-size:14px;font-weight:650;text-overflow:ellipsis;white-space:nowrap; }
.operator-title span { color:#7e8998;font-size:10px; }
.operator-summary { display:-webkit-box;min-height:36px;margin:13px 0;color:#646f7e;font-size:11px;line-height:1.65;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:2; }
.operator-facts { display:grid;grid-template-columns:1fr 1fr;border:1px solid #edf0f4;border-radius:8px;background:#fafbfc; }
.operator-facts > div { min-width:0;display:flex;flex-direction:column;gap:4px;padding:8px 10px; }
.operator-facts > div + div { border-left:1px solid #edf0f4; }
.operator-facts span { color:#8a94a2;font-size:9px; }
.operator-facts strong { overflow:hidden;color:#4a5565;font-size:10px;font-weight:600;text-overflow:ellipsis;white-space:nowrap; }
.operator-actions { display:flex;align-items:center;justify-content:flex-end;gap:4px;margin-top:auto;padding-top:9px; }
.operator-owner { margin-right:auto;color:#8a94a2;font-size:10px; }
.type-symbol { width:36px;height:36px;display:inline-grid;place-items:center;flex-shrink:0;border:1px solid #bfdbfe;border-radius:8px;background:#eff6ff;color:#1d4ed8;font-size:12px;font-weight:800; }
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
@media (max-width: 900px) { .library-stats { grid-template-columns: repeat(2, 1fr);padding-inline:14px; } .library-filters { align-items: stretch; flex-direction: column;padding-inline:14px; } .search-input { width: 100%; } .operator-type-tabs { overflow-x: auto; padding-inline: 14px; }.operator-grid{padding-inline:14px}.type-choice-grid { grid-template-columns: 1fr; } .type-choice:last-child { grid-column: auto; } }
@media (max-width: 560px) { .library-toolbar{align-items:stretch;flex-direction:column;padding-inline:14px}.toolbar-actions{display:grid;grid-template-columns:1fr 1fr}.toolbar-actions .el-button:last-child{grid-column:1/-1}.library-stats{grid-template-columns:1fr 1fr}.operator-grid{grid-template-columns:1fr}.operator-card{min-height:188px} }
</style>
