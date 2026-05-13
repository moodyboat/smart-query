<template>
  <div class="dashboard-fullscreen" v-if="visible">
    <div class="dashboard-toolbar">
      <div class="toolbar-left">
        <span class="dashboard-fullscreen-title">{{ dashboard?.title || '仪表盘' }}</span>
      </div>
      <div class="toolbar-center">
        <template v-for="(w, wi) in filterWidgets" :key="wi">
          <el-select
            v-if="w.type === 'select'"
            :placeholder="w.label"
            v-model="filterValues[w.field]"
            size="small"
            clearable
            style="width: 140px"
          >
            <el-option v-for="opt in w.options" :key="opt" :label="opt" :value="opt" />
          </el-select>
          <el-date-picker
            v-if="w.type === 'daterange'"
            type="daterange"
            :start-placeholder="w.label"
            size="small"
            v-model="filterValues[w.field]"
            value-format="YYYY-MM-DD"
          />
          <el-input
            v-if="w.type === 'search'"
            v-model="filterValues[w.field]"
            :placeholder="w.label"
            size="small"
            clearable
            style="width: 160px"
          />
        </template>
        <el-button
          v-if="filterWidgets.length"
          type="primary"
          size="small"
          @click="applyAllFilters"
          :loading="filterLoading"
        >
          刷新数据
        </el-button>
      </div>
      <div class="toolbar-right">
        <el-button size="small" @click="$emit('close')">关闭</el-button>
      </div>
    </div>
    <div class="dashboard-body" :class="layoutClass">
      <div v-for="chart in charts" :key="chart.id" class="dashboard-chart-card">
        <EChartsRenderer :title="chart.title" :option="chart.echartsOption" />
      </div>
      <div v-if="loading" class="dashboard-full-loading">
        <span class="spinner"></span> 加载仪表盘数据...
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import EChartsRenderer from './EChartsRenderer.vue'
import { fetchDashboardWithCharts, rerenderChart } from '../api'

const props = defineProps({
  visible: { type: Boolean, default: false },
  dashboardId: { type: Number, default: null }
})

const emit = defineEmits(['close'])

const dashboard = ref(null)
const charts = ref([])
const loading = ref(false)
const filterLoading = ref(false)
const filterValues = ref({})
const filterWidgets = computed(() => {
  if (!dashboard.value?.filterWidgets) return []
  try {
    const parsed = typeof dashboard.value.filterWidgets === 'string'
      ? JSON.parse(dashboard.value.filterWidgets)
      : dashboard.value.filterWidgets
    if (Array.isArray(parsed)) return parsed
    return parsed.widgets || []
  } catch {
    return []
  }
})

const layoutClass = computed(() => {
  const layout = dashboard.value?.layout
  if (!layout) return 'grid-2col'
  try {
    const parsed = typeof layout === 'string' ? JSON.parse(layout) : layout
    return parsed?.type || 'grid-2col'
  } catch {
    return 'grid-2col'
  }
})

async function loadDashboard() {
  if (!props.dashboardId) return
  loading.value = true
  try {
    const result = await fetchDashboardWithCharts(props.dashboardId)
    if (result) {
      dashboard.value = result.dashboard
      charts.value = (result.charts || []).map(c => ({
        id: c.id,
        title: c.title,
        echartsOption: c.echartsOption,
        baseSql: c.baseSql
      }))
    }
  } catch (e) {
    console.warn('Dashboard load failed:', e)
  } finally {
    loading.value = false
  }
}

async function applyAllFilters() {
  filterLoading.value = true
  try {
    const updates = charts.value.filter(c => c.baseSql).map(async (chart) => {
      try {
        const resp = await rerenderChart(chart.id, filterValues.value)
        if (resp?.echartsOption) {
          charts.value = charts.value.map(c =>
            c.id === chart.id ? { ...c, echartsOption: resp.echartsOption } : c
          )
        } else if (resp?.rows?.length) {
          const newOption = rebuildOption(chart, resp.rows)
          if (newOption) {
            charts.value = charts.value.map(c =>
              c.id === chart.id ? { ...c, echartsOption: newOption } : c
            )
          }
        }
      } catch { /* skip failed charts */ }
    })
    await Promise.all(updates)
  } finally {
    filterLoading.value = false
  }
}

function rebuildOption(chart, rows) {
  const opt = typeof chart.echartsOption === 'string'
    ? JSON.parse(chart.echartsOption)
    : { ...chart.echartsOption }
  const columns = Object.keys(rows[0])
  if (opt.series?.length && columns.length >= 2) {
    const xCol = columns[0]
    const yCols = columns.slice(1)
    if (opt.xAxis) {
      opt.xAxis = { ...opt.xAxis, data: rows.map(r => r[xCol]) }
    }
    opt.series = opt.series.map((s, i) => ({
      ...s,
      data: i < yCols.length ? rows.map(r => r[yCols[i]]) : s.data
    }))
  }
  return opt
}

watch(() => props.visible, (v) => {
  if (v && props.dashboardId) loadDashboard()
})

watch(() => props.dashboardId, (id) => {
  if (props.visible && id) loadDashboard()
})
</script>

<style scoped>
.dashboard-fullscreen {
  position: fixed; inset: 0; z-index: 1000;
  background: #f5f6fa; display: flex; flex-direction: column;
}
.dashboard-toolbar {
  height: 52px; background: #fff; border-bottom: 1px solid #e0e0e0;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px; flex-shrink: 0;
}
.toolbar-left, .toolbar-right { display: flex; align-items: center; gap: 8px; }
.toolbar-center { display: flex; align-items: center; gap: 10px; }
.dashboard-fullscreen-title { font-size: 15px; font-weight: 600; color: #333; }

.dashboard-body {
  flex: 1; padding: 16px; gap: 16px; overflow-y: auto;
}
.grid-2col { display: grid; grid-template-columns: repeat(2, 1fr); }
.grid-3col { display: grid; grid-template-columns: repeat(3, 1fr); }

.dashboard-chart-card {
  background: #fff; border-radius: 8px; overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}

.dashboard-full-loading {
  grid-column: 1 / -1; padding: 40px; text-align: center;
  color: #999; display: flex; align-items: center; justify-content: center; gap: 8px;
}
.spinner {
  width: 14px; height: 14px; border: 2px solid #ddd; border-top-color: #409eff;
  border-radius: 50%; animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
