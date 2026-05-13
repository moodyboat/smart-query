<template>
  <div class="dashboard-container">
    <div class="dashboard-header">
      <span>{{ title }}</span>
    </div>
    <div v-if="filterWidgets.length" class="dashboard-filters">
      <template v-for="(w, wi) in filterWidgets" :key="wi">
        <el-select
          v-if="w.type === 'select'"
          :placeholder="w.label"
          v-model="filterValues[w.field]"
          size="small"
          clearable
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
          style="width: 140px"
        />
      </template>
      <el-button
        type="primary"
        size="small"
        :loading="filterLoading"
        @click="applyFilters"
      >
        应用筛选
      </el-button>
    </div>
    <div :class="['dashboard-grid', layoutClass]">
      <div v-for="chart in resolvedCharts" :key="chart.id" class="dashboard-chart-cell">
        <EChartsRenderer :title="chart.title" :option="chart.echartsOption" />
      </div>
      <div v-if="loading" class="dashboard-loading">
        <span class="spinner"></span> 加载图表...
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import EChartsRenderer from './EChartsRenderer.vue'
import { fetchDashboardWithCharts, rerenderChart } from '../api'

const props = defineProps({
  title: String,
  layout: { type: String, default: 'grid-2col' },
  charts: { type: Array, default: () => [] },
  dashboardId: { type: Number, default: null },
  pendingCharts: { type: Object, default: () => ({}) },
  filterData: { type: Object, default: null }
})

const emit = defineEmits(['filterApplied'])

const loading = ref(false)
const filterLoading = ref(false)
const fetchedCharts = ref([])
const filterValues = ref({})
const chartOverrides = reactive({})

const layoutClass = computed(() => {
  try {
    const parsed = typeof props.layout === 'string' ? JSON.parse(props.layout) : props.layout
    return parsed?.type || 'grid-2col'
  } catch {
    return props.layout || 'grid-2col'
  }
})

const filterWidgets = computed(() => {
  if (props.filterData?.widgets) return props.filterData.widgets
  return []
})

const resolvedCharts = computed(() => {
  const source = props.charts.length > 0 ? props.charts
    : fetchedCharts.value.length > 0 ? fetchedCharts.value : []
  return source.map(c => {
    if (chartOverrides[c.id]) {
      return { ...c, echartsOption: chartOverrides[c.id] }
    }
    return c
  })
})

async function applyFilters() {
  if (!resolvedCharts.value.length) return
  filterLoading.value = true
  try {
    const source = props.charts.length > 0 ? props.charts
      : fetchedCharts.value.length > 0 ? fetchedCharts.value : []
    const updates = source.map(async (chart) => {
      if (!chart.id) return
      try {
        const resp = await rerenderChart(chart.id, filterValues.value)
        if (resp?.echartsOption) {
          chartOverrides[chart.id] = resp.echartsOption
        } else if (resp?.rows?.length) {
          const newOption = rebuildOption(chart, resp.rows)
          if (newOption) {
            chartOverrides[chart.id] = newOption
          }
        }
      } catch { /* skip failed charts */ }
    })
    await Promise.all(updates)
    emit('filterApplied', filterValues.value)
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

async function loadChartsFromBackend() {
  if (!props.dashboardId || props.charts.length > 0) return
  loading.value = true
  try {
    const result = await fetchDashboardWithCharts(props.dashboardId)
    if (result?.charts) {
      fetchedCharts.value = result.charts.map(c => ({
        id: c.id,
        title: c.title,
        echartsOption: c.echartsOption
      }))
    }
  } catch (e) {
    console.warn('Dashboard chart fetch failed:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (props.charts.length === 0 && props.dashboardId) {
    loadChartsFromBackend()
  }
})

watch(() => props.charts, (newCharts) => {
  if (newCharts.length > 0) {
    fetchedCharts.value = []
  }
})
</script>

<style scoped>
.dashboard-container {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}
.dashboard-header {
  padding: 12px 16px;
  font-size: 15px;
  font-weight: 600;
  color: #333;
  border-bottom: 1px solid #f0f0f0;
}
.dashboard-filters {
  display: flex; gap: 8px; padding: 8px 12px; flex-wrap: wrap;
  align-items: center; border-bottom: 1px solid #f5f5f5;
}
.dashboard-grid {
  padding: 12px;
  gap: 12px;
}
.grid-2col {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
}
.grid-3col {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
}
.dashboard-chart-cell {
  min-width: 0;
}
.dashboard-loading {
  padding: 20px;
  text-align: center;
  color: #999;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.spinner {
  width: 14px; height: 14px; border: 2px solid #ddd; border-top-color: #409eff;
  border-radius: 50%; animation: spin 0.6s linear infinite; flex-shrink: 0;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
