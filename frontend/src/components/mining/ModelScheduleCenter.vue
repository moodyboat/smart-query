<template>
  <section class="schedule-center">
    <div class="schedule-toolbar">
      <el-input v-model="search" placeholder="搜索模型成品、算法或数据表" clearable prefix-icon="Search" />
      <div class="toolbar-stat"><strong>{{ finishedModels.length }}</strong><span>已发布</span></div>
      <div class="toolbar-stat"><strong>{{ orchestrationReadyCount }}</strong><span>可编排</span></div>
    </div>

    <div v-if="props.loading" class="center-state">正在加载模型成品…</div>
    <div v-else-if="!finishedModels.length" class="center-state empty-state">
      <span>◇</span>
      <h4>暂无已发布的模型成品</h4>
    </div>
    <div v-else-if="!filteredModels.length" class="center-state empty-state">
      <h4>没有匹配的模型成品</h4>
    </div>
    <div v-else class="artifact-grid">
      <article v-for="model in filteredModels" :key="model.id" class="artifact-card" tabindex="0"
        @click="emit('openDag', model)" @keydown.enter="emit('openDag', model)">
        <header>
          <div class="artifact-title">
            <span class="artifact-icon">✦</span>
            <div><strong>{{ model.name }}</strong><small>模型 #{{ model.id }} · 版本 {{ model.version }}</small></div>
          </div>
          <el-tag type="success" effect="plain">已发布</el-tag>
        </header>

        <div class="artifact-meta">
          <span>{{ props.algorithmLabel(model.algorithm) }}</span>
          <span>{{ props.modelTypeLabel(model.modelType) }}</span>
          <span>表：{{ model.sourceTable || '-' }}</span>
        </div>

        <div class="artifact-metric">
          <span>{{ props.primaryMetricLabel(model) }}</span>
          <strong>{{ props.primaryMetricValue(model) }}</strong>
          <small>最近训练指标</small>
        </div>

        <div class="artifact-status">
          <div>
            <span>制品摘要</span>
            <strong>{{ shortHash(model.artifactSha256) }}</strong>
          </div>
          <div>
            <span>最近更新</span>
            <strong>{{ props.formatDate(model.updatedAt) }}</strong>
          </div>
          <div>
            <span>编排授权</span>
            <strong :class="{ ready: orchestrationReady(model) }">
              {{ orchestrationReady(model) ? '已通过审批' : '待算子审批' }}
            </strong>
          </div>
        </div>

        <footer>
          <span class="owner-scope">{{ ownerLabel(model) }}</span>
          <el-button size="small" type="primary" :disabled="!orchestrationReady(model)"
            @click.stop="emit('openDag', model)">进入流程编排</el-button>
        </footer>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { MODEL_STATUS } from '../../constants.js'

const props = defineProps({
  models: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  dataSources: { type: Array, default: () => [] },
  catalogItems: { type: Array, default: () => [] },
  isAdmin: { type: Boolean, default: false },
  algorithmLabel: { type: Function, required: true },
  modelTypeLabel: { type: Function, required: true },
  primaryMetricLabel: { type: Function, required: true },
  primaryMetricValue: { type: Function, required: true },
  formatDate: { type: Function, required: true }
})
const emit = defineEmits(['openDag'])
const search = ref('')

const finishedModels = computed(() => props.models.filter(model =>
  model.status === MODEL_STATUS.PUBLISHED && model.modelPath && model.artifactSha256))
const filteredModels = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return finishedModels.value
  return finishedModels.value.filter(model => [model.name, model.algorithm, model.sourceTable, model.description]
    .some(value => String(value || '').toLowerCase().includes(keyword)))
})
const orchestrationReadyCount = computed(() => finishedModels.value.filter(orchestrationReady).length)

function operatorVersion(model) {
  return props.catalogItems.find(item => item.code === `ml_model_${model.id}`)
}
function orchestrationReady(model) { return Boolean(operatorVersion(model)) }
function shortHash(value) { return value ? `${String(value).slice(0, 12)}…` : '—' }
function ownerLabel(model) {
  return props.isAdmin ? `归属用户 #${model.userId || '-'}` : '本人模型'
}
</script>

<style scoped>
.schedule-center{display:flex;min-height:0;flex-direction:column;gap:16px}.schedule-intro{display:flex;align-items:stretch;gap:16px;padding:18px 20px;border:1px solid #dbe7f5;border-radius:12px;background:linear-gradient(135deg,#f7fbff,#eef5ff)}.schedule-intro>div:first-child{flex:1}.eyebrow{color:#3b7bc5;font-size:10px;font-weight:700;letter-spacing:.12em}.schedule-intro h3{margin:5px 0 4px;color:#12345c;font-size:18px}.schedule-intro p{margin:0;color:#687990;font-size:12px}.permission-note{min-width:310px;display:flex;flex-direction:column;justify-content:center;gap:4px;padding:12px 15px;border-left:3px solid #3b82f6;border-radius:7px;background:rgba(255,255,255,.82)}.permission-note strong{color:#174d8d;font-size:12px}.permission-note span{color:#415873;font-size:12px}.permission-note small{color:#7c8ba0;font-size:10px}.schedule-toolbar{display:flex;align-items:center;gap:10px}.schedule-toolbar>.el-input{width:300px;margin-right:auto}.toolbar-stat{min-width:86px;display:flex;align-items:baseline;justify-content:center;gap:5px;padding:8px 12px;border:1px solid #e0e8f2;border-radius:9px;background:white}.toolbar-stat strong{color:#175cb5;font-size:18px}.toolbar-stat span{color:#78869a;font-size:11px}.center-state{display:grid;min-height:280px;place-items:center;color:#6f7f94}.empty-state{align-content:center;gap:7px;text-align:center}.empty-state>span{color:#7ba9e2;font-size:36px}.empty-state h4,.empty-state p{margin:0}.empty-state h4{color:#294664}.empty-state p{font-size:12px}.artifact-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(410px,1fr));gap:14px;overflow:auto;padding:1px 3px 12px}.artifact-card{display:flex;flex-direction:column;gap:14px;padding:17px;border:1px solid #dfe7f1;border-radius:11px;background:white;box-shadow:0 2px 7px rgba(35,73,120,.04);cursor:pointer;transition:.2s}.artifact-card:hover,.artifact-card:focus{border-color:#74a8e7;box-shadow:0 8px 20px rgba(43,101,174,.1);outline:none;transform:translateY(-1px)}.artifact-card header,.artifact-card footer{display:flex;align-items:center;gap:9px}.artifact-card header{justify-content:space-between}.artifact-title{display:flex;align-items:center;gap:10px;min-width:0}.artifact-title>div{display:flex;min-width:0;flex-direction:column;gap:2px}.artifact-title strong{overflow:hidden;color:#17365d;font-size:15px;text-overflow:ellipsis;white-space:nowrap}.artifact-title small{color:#8794a6;font-size:10px}.artifact-icon{width:32px;height:32px;display:grid;flex:none;place-items:center;border-radius:9px;background:#eaf3ff;color:#2563eb}.artifact-meta{display:flex;flex-wrap:wrap;gap:7px}.artifact-meta span{padding:3px 8px;border-radius:5px;background:#f2f5f9;color:#63748a;font-size:10px}.artifact-metric{display:flex;align-items:baseline;gap:7px}.artifact-metric span{color:#63748a;font-size:11px}.artifact-metric strong{color:#175cb5;font-size:24px}.artifact-metric small{color:#95a0ae;font-size:10px}.artifact-status{display:grid;grid-template-columns:1.1fr 1.2fr 1fr;border:1px solid #e7edf5;border-radius:8px;background:#f9fbfd}.artifact-status>div{display:flex;min-width:0;flex-direction:column;gap:4px;padding:10px;border-right:1px solid #e7edf5}.artifact-status>div:last-child{border-right:0}.artifact-status span{color:#8491a3;font-size:9px}.artifact-status strong{overflow:hidden;color:#415873;font-size:10px;text-overflow:ellipsis;white-space:nowrap}.artifact-status strong.ready{color:#16845b}.artifact-card footer{margin-top:auto;padding-top:4px}.owner-scope{margin-right:auto;color:#7b899b;font-size:10px}@media(max-width:900px){.schedule-intro{flex-direction:column}.permission-note{min-width:0}.artifact-grid{grid-template-columns:1fr}.schedule-toolbar{flex-wrap:wrap}.schedule-toolbar>.el-input{width:100%;margin-right:0}}
.schedule-center{gap:12px}.toolbar-stat{border-color:#e4e8ef;border-radius:9px;background:#fff}.artifact-grid{gap:10px}.artifact-card{gap:12px;padding:13px;border-color:#e4e8ef;border-radius:9px;background:#fff;box-shadow:none}.artifact-card:hover,.artifact-card:focus{border-color:#a9c3f8;box-shadow:none;transform:none}.artifact-icon{border-radius:8px;background:#edf3ff}.artifact-status{border-color:#e4e8ef;background:#fafbfc}
</style>
