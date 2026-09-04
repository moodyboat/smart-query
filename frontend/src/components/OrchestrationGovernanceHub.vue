<template>
  <div class="orchestration-hub">
    <button v-if="props.showSidebarToggle" type="button" class="hub-menu-floating" aria-label="打开导航" @click="emit('toggleSidebar')">☰</button>

    <main class="hub-content">
      <section v-if="activeSection === 'schedule' || activeSection === 'pipelines'" class="domain-shell">
        <div class="secondary-nav" role="tablist" :aria-label="`${activeSectionLabel}二级功能`">
          <button type="button" role="tab" :aria-selected="secondaryView === 'home'"
            :class="{ active: secondaryView === 'home' }" @click="secondaryView = 'home'">
            {{ activeSection === 'schedule' ? '模型资产' : '训练实验' }}
          </button>
          <button type="button" role="tab" :aria-selected="secondaryView === 'dag'"
            :class="{ active: secondaryView === 'dag' }" @click="openBlankDag">
            流程编排
          </button>
          <el-select v-if="activeSection === 'schedule' && secondaryView === 'home'"
            v-model="mining.filterDsId" class="secondary-filter" placeholder="全部模型数据源"
            aria-label="模型数据源筛选" size="small" clearable>
            <el-option v-for="ds in mining.dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>
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
        <el-segmented v-model="governanceSection" class="governance-section-nav"
          :options="governanceOptions" aria-label="模型治理模块" />
        <div class="governance-content">
          <ModelApprovalCenter v-if="governanceSection === 'model-approvals'" />
          <OperatorApprovalCenter v-else-if="governanceSection === 'operator-approvals'" @openDag="openDag" />
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
import ModelApprovalCenter from './ModelApprovalCenter.vue'
import OperatorApprovalCenter from './OperatorApprovalCenter.vue'
import OperatorLibraryCenter from './OperatorLibraryCenter.vue'
import StorageGovernanceCenter from './StorageGovernanceCenter.vue'
import V2DagDesigner from './V2DagDesigner.vue'
import { useMiningStore } from '../stores/mining.js'
import { useUserStore } from '../stores/user.js'

const props = defineProps({
  conversationId: { type: Number, default: null },
  initialSection: { type: String, default: 'schedule' },
  initialOperatorVersionId: { type: Number, default: null },
  showSidebarToggle: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'toggleSidebar'])
const mining = useMiningStore()
const navItems = [
  { value: 'schedule', label: '模型中心' },
  { value: 'pipelines', label: '训练流水线' },
  { value: 'operators', label: '算子库' },
  { value: 'governance', label: '模型治理' }
]
const user = useUserStore()
const governanceOptions = computed(() => [
  { label: '模型版本审批', value: 'model-approvals' },
  { label: '算子版本审批', value: 'operator-approvals' },
  { label: '依赖管理', value: 'dependencies' },
  ...(user.canManageRuntime ? [{ label: '存储与运行', value: 'storage' }] : [])
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
const governanceSection = ref('model-approvals')
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
.orchestration-hub {
  position: relative;
  min-width: 0;
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #e4e8ef;
  border-radius: 12px;
  color: var(--text-primary);
  background: #f5f7fa;
  box-shadow: 0 4px 16px rgba(31,35,41,.05);
}
.hub-menu-floating {
  position: absolute;
  z-index: 8;
  top: 9px;
  left: 9px;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 1px solid rgba(60,60,67,.12);
  border-radius: 9px;
  color: #68686d;
  background: rgba(255,255,255,.94);
  cursor: pointer;
}
.hub-header {
  min-height: 78px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
  padding: 12px 21px;
  border-bottom: 1px solid #e4e8ef;
  background: #fff;
  z-index: 4;
}
.hub-menu {
  width: 31px;
  height: 31px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 9px;
  color: #68686d;
  background: rgba(118,118,128,.08);
  cursor: pointer;
}
.hub-mark {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 9px;
  color: white;
  background: #2468f2;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: .06em;
}
.hub-brand { min-width: 0; flex: 1; display: grid; grid-template-columns: max-content 1fr; align-items: baseline; column-gap: 9px; }
.hub-brand small { grid-column: 1 / -1; margin-bottom: 2px; color: #96969c; font-size: 8px; font-weight: 700; letter-spacing: .11em; }
.hub-brand strong { color: #1d1d1f; font-size: 15px; font-weight: 660; letter-spacing: -.02em; }
.hub-brand p { overflow: hidden; margin: 0; color: #7d7d82; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.governance-header-nav { --el-segmented-bg-color: #f0f2f5; --el-segmented-item-selected-bg-color: #fff; --el-segmented-item-selected-color: #2468f2; flex-shrink: 0; border-radius: 8px; }
.governance-header-nav :deep(.el-segmented__item) { min-width: 104px; font-size: 11px; }

.hub-content { min-height: 0; flex: 1; display: flex; overflow: hidden; }
.domain-shell { min-width: 0; min-height: 0; flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.secondary-nav {
  min-height: 49px;
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  padding: 7px 20px;
  border-bottom: 1px solid rgba(60,60,67,.08);
  background: #fff;
}
.secondary-nav button {
  height: 32px;
  padding: 0 13px;
  border: 0;
  border-radius: 8px;
  color: #737378;
  background: transparent;
  font: inherit;
  font-size: 10px;
  cursor: pointer;
}
.secondary-nav button:hover { color: #1d1d1f; background: rgba(118,118,128,.07); }
.secondary-nav button.active { color: #2468f2; background: #edf3ff; box-shadow: inset 0 0 0 1px #d7e3fb; font-weight: 620; }
.secondary-filter { width: 190px; margin-left: auto; }
.secondary-filter :deep(.el-select__wrapper) { min-height: 32px; border-radius: 8px; background: #fff; }
.dag-context { margin-left: auto; padding: 5px 9px; border-radius: 7px; color: #6e6e73; background: rgba(118,118,128,.07); font-size: 8.5px; }
.domain-shell > :deep(.mining-manager), .domain-shell > :deep(.dag-designer) { min-height: 0; flex: 1; }

.governance-shell { min-height: 0; flex: 1; display: flex; flex-direction: column; }
.governance-section-nav { --el-segmented-bg-color: #f0f2f5; --el-segmented-item-selected-bg-color: #fff; --el-segmented-item-selected-color: #2468f2; align-self: flex-start; flex-shrink: 0; margin: 12px 16px 8px; border-radius: 8px; }
.governance-section-nav :deep(.el-segmented__item) { min-width: 104px; font-size: 11px; }
.governance-content { min-height: 0; flex: 1; display: flex; overflow: hidden; }
.governance-content > :deep(*) { min-width: 0; flex: 1; }

:deep(.page-container),
:deep(.operator-library-center),
:deep(.dependency-center) { background: transparent !important; }
:deep(.embedded .page-header) { border-bottom: 1px solid rgba(60,60,67,.08); background: rgba(255,255,255,.66) !important; }
:deep(.page-title) { color: #1d1d1f; font-size: 16px; letter-spacing: -.02em; }
:deep(.repository-summary > div),
:deep(.library-stats > div),
:deep(.operator-card) { border-color: #e4e8ef !important; border-radius: 9px !important; box-shadow: none !important; }

@media (max-width: 850px) {
  .orchestration-hub { border: 0; border-radius: 0; }
  .hub-content { padding-top: 48px; }
  .governance-section-nav { width: calc(100% - 20px); margin: 8px 10px; }
  .governance-section-nav :deep(.el-segmented__group) { min-width: 100%; width: 100%; }
  .governance-section-nav :deep(.el-segmented__item) { min-width: 0; flex: 1; padding-inline: 3px; font-size: 9.5px; }
  .secondary-nav { padding-inline: 10px; overflow-x: auto; }
  .secondary-filter { min-width: 158px; width: 158px; }
}
</style>
