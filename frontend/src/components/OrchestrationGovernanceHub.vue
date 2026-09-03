<template>
  <div class="orchestration-hub">
    <header class="hub-header">
      <button type="button" class="hub-back" @click="emit('close')">← 返回问数</button>
      <div class="hub-brand">
        <span class="hub-mark">SQ</span>
        <div><strong>编排与治理</strong><small>Operator &amp; Flow Control Center</small></div>
      </div>
      <nav class="hub-nav" aria-label="编排与治理功能导航">
        <button v-for="item in navItems" :key="item.value" type="button"
          :class="{ active: activeSection === item.value }" @click="selectSection(item.value)">
          <span>{{ item.icon }}</span>{{ item.label }}
        </button>
      </nav>
    </header>

    <main class="hub-content">
      <section v-if="activeSection === 'schedule' || activeSection === 'pipelines'" class="domain-shell">
        <div class="secondary-nav" role="tablist" :aria-label="`${activeSectionLabel}二级功能`">
          <button type="button" role="tab" :aria-selected="secondaryView === 'home'"
            :class="{ active: secondaryView === 'home' }" @click="secondaryView = 'home'">
            {{ activeSection === 'schedule' ? '调度管理' : '流水线管理' }}
          </button>
          <button type="button" role="tab" :aria-selected="secondaryView === 'dag'"
            :class="{ active: secondaryView === 'dag' }" @click="openBlankDag">
            流程编排
          </button>
          <span v-if="secondaryView === 'dag' && dagContextLabel" class="dag-context">来源：{{ dagContextLabel }}</span>
        </div>
        <MiningManager v-if="secondaryView === 'home'" embedded repository-mode
          :repository-section="activeSection === 'schedule' ? 'schedule' : 'pipeline'"
          @openDag="openDag" />
        <KeepAlive v-else>
          <V2DagDesigner :key="dagKey" embedded :initial-operator-version-id="dagVersionId"
            @createOperator="openOperatorCreator" />
        </KeepAlive>
      </section>

      <OperatorLibraryCenter v-else-if="activeSection === 'operators'" ref="operatorLibrary"
        :conversation-id="props.conversationId" @openDag="openDag" />

      <section v-else-if="activeSection === 'governance'" class="governance-shell">
        <div class="governance-nav">
          <div><h2>治理中心</h2><p>版本审批、依赖运行时和生产存储在同一治理域内闭环。</p></div>
          <el-segmented v-model="governanceSection" :options="governanceOptions" />
        </div>
        <div class="governance-content">
          <OperatorApprovalCenter v-if="governanceSection === 'approvals'" @openDag="openDag" />
          <DependencyCenter v-else-if="governanceSection === 'dependencies'" embedded />
          <StorageGovernanceCenter v-else />
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import DependencyCenter from './DependencyCenter.vue'
import MiningManager from './MiningManager.vue'
import OperatorApprovalCenter from './OperatorApprovalCenter.vue'
import OperatorLibraryCenter from './OperatorLibraryCenter.vue'
import StorageGovernanceCenter from './StorageGovernanceCenter.vue'
import V2DagDesigner from './V2DagDesigner.vue'
import { useUserStore } from '../stores/user.js'

const props = defineProps({
  conversationId: { type: Number, default: null },
  initialSection: { type: String, default: 'schedule' },
  initialOperatorVersionId: { type: Number, default: null }
})
const emit = defineEmits(['close'])
const navItems = [
  { value: 'schedule', label: '调度中心', icon: '◷' },
  { value: 'pipelines', label: '模型流水线', icon: '⌘' },
  { value: 'operators', label: '算子库', icon: '◇' },
  { value: 'governance', label: '治理中心', icon: '✓' }
]
const user = useUserStore()
const governanceOptions = computed(() => [
  { label: '版本审批', value: 'approvals' },
  { label: '依赖与运行时', value: 'dependencies' },
  ...(user.isAdmin ? [{ label: '存储与运行', value: 'storage' }] : [])
])
const normalizeSection = value => {
  if (value === 'models') return 'schedule'
  if (value === 'mining') return 'pipelines'
  if (value === 'orchestration' || value === 'results') return 'pipelines'
  return ['schedule', 'pipelines', 'operators', 'governance'].includes(value) ? value : 'schedule'
}
const startsInDag = value => ['orchestration', 'results'].includes(value) || Boolean(props.initialOperatorVersionId)
const activeSection = ref(normalizeSection(props.initialSection))
const secondaryView = ref(startsInDag(props.initialSection) ? 'dag' : 'home')
const governanceSection = ref('approvals')
const dagVersionId = ref(props.initialOperatorVersionId)
const dagContext = ref(null)
const dagKey = ref(0)
const operatorLibrary = ref(null)
const activeSectionLabel = computed(() => navItems.find(item => item.value === activeSection.value)?.label || '')
const dagContextLabel = computed(() => dagContext.value?.sourceName || '')

function selectSection(section) {
  activeSection.value = section
  secondaryView.value = 'home'
  dagVersionId.value = null
  dagContext.value = null
}

function openBlankDag() {
  if (secondaryView.value === 'dag' && !dagVersionId.value) return
  secondaryView.value = 'dag'
  dagVersionId.value = null
  dagContext.value = { sourceType: 'DIRECT', sourceName: activeSectionLabel.value }
  dagKey.value += 1
}

async function openOperatorCreator() {
  selectSection('operators')
  await nextTick()
  operatorLibrary.value?.openCreate()
}

function openDag(request) {
  const payload = typeof request === 'object' && request !== null
    ? request
    : { operatorVersionId: request || null }
  if (!['schedule', 'pipelines'].includes(activeSection.value)) activeSection.value = 'pipelines'
  dagVersionId.value = payload.operatorVersionId || null
  dagContext.value = payload
  dagKey.value += 1
  secondaryView.value = 'dag'
}

watch(() => props.initialSection, value => {
  if (!value) return
  activeSection.value = normalizeSection(value)
  secondaryView.value = startsInDag(value) ? 'dag' : 'home'
})
watch(() => props.initialOperatorVersionId, value => {
  dagVersionId.value = value || null
  if (value) {
    dagContext.value = { operatorVersionId: value, sourceType: 'OPERATOR', sourceName: `算子版本 #${value}` }
    dagKey.value += 1
    activeSection.value = 'pipelines'
    secondaryView.value = 'dag'
  }
})
</script>

<style scoped>
.orchestration-hub { flex: 1; min-width: 0; height: 100vh; display: flex; flex-direction: column; overflow: hidden; background: #f6f8fc; color: #172b4d; }
.hub-header { min-height: 68px; display: flex; align-items: center; gap: 18px; padding: 0 20px; border-bottom: 1px solid #dfe7f1; background: rgba(255,255,255,.98); box-shadow: 0 1px 3px rgba(30, 64, 120, .04); z-index: 4; }
.hub-back { height: 36px; padding: 0 14px; border: 1px solid #d7e2f0; border-radius: 8px; background: white; color: #356da8; cursor: pointer; }
.hub-back:hover { border-color: #8bb7ef; background: #f5f9ff; color: #175cb5; }
.hub-brand { display: flex; align-items: center; gap: 10px; min-width: 190px; }
.hub-brand > div { display: flex; flex-direction: column; gap: 2px; }
.hub-brand strong { color: #102a4c; font-size: 17px; letter-spacing: .02em; }
.hub-brand small { color: #8793a5; font-size: 9px; letter-spacing: .08em; text-transform: uppercase; }
.hub-mark { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 9px; background: #1f63c3; color: white; font-size: 11px; font-weight: 800; letter-spacing: .04em; box-shadow: 0 4px 12px rgba(37,99,235,.18); }
.hub-nav { align-self: stretch; display: flex; align-items: stretch; gap: 2px; margin-left: auto; }
.hub-nav button { position: relative; min-width: 102px; display: flex; align-items: center; justify-content: center; gap: 7px; padding: 0 13px; border: 0; background: transparent; color: #65758b; font: inherit; font-size: 13px; cursor: pointer; }
.hub-nav button::after { content: ''; position: absolute; right: 13px; bottom: 0; left: 13px; height: 2px; border-radius: 2px 2px 0 0; background: transparent; }
.hub-nav button:hover { color: #1d5eae; background: #f8fbff; }
.hub-nav button.active { color: #175cb5; font-weight: 600; }
.hub-nav button.active::after { background: #2563eb; }
.hub-nav button span { color: #4381cd; font-size: 15px; }
.hub-content { flex: 1; min-height: 0; display: flex; overflow: hidden; }
.domain-shell { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.secondary-nav { min-height: 46px; display: flex; align-items: stretch; gap: 3px; padding: 0 22px; border-bottom: 1px solid #e1e8f1; background: #fbfcfe; }
.secondary-nav button { position: relative; padding: 0 18px; border: 0; background: transparent; color: #718096; font: inherit; font-size: 12px; cursor: pointer; }
.secondary-nav button::after { content: ''; position: absolute; right: 16px; bottom: 0; left: 16px; height: 2px; border-radius: 2px 2px 0 0; background: transparent; }
.secondary-nav button:hover { color: #1f63c3; background: #f5f9ff; }
.secondary-nav button.active { color: #175cb5; font-weight: 650; }
.secondary-nav button.active::after { background: #2563eb; }
.dag-context { align-self: center; margin-left: auto; padding: 5px 10px; border: 1px solid #dbe7f5; border-radius: 6px; background: white; color: #65758b; font-size: 10px; }
.domain-shell > :deep(.mining-manager), .domain-shell > :deep(.dag-designer) { flex: 1; min-height: 0; }
.governance-shell { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.governance-nav { min-height: 76px; display: flex; align-items: center; gap: 18px; padding: 13px 22px; border-bottom: 1px solid #e1e8f1; background: white; }
.governance-nav > div:first-child { flex: 1; }
.governance-nav h2 { margin: 0 0 4px; color: #102a4c; font-size: 18px; }
.governance-nav p { margin: 0; color: #778499; font-size: 12px; }
.governance-content { flex: 1; min-height: 0; display: flex; overflow: hidden; }
.governance-content > :deep(*) { flex: 1; min-width: 0; }
@media (max-width: 1180px) { .hub-header { gap: 10px; padding-inline: 12px; } .hub-brand { min-width: 150px; } .hub-nav button { min-width: 82px; padding-inline: 8px; } .hub-nav button span { display: none; } }
@media (max-width: 850px) { .hub-header { flex-wrap: wrap; height: auto; padding-top: 8px; } .hub-brand { flex: 1; } .hub-nav { width: 100%; height: 44px; order: 3; overflow-x: auto; } .hub-nav button { flex: 1; min-width: 86px; } }
</style>
