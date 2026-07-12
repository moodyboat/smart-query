<template>
  <div :class="['chart-container']">
    <div class="chart-title">{{ title }}</div>
    <div v-if="chartError" class="chart-error">
      <span class="chart-error-icon">⚠</span>
      <span>图表渲染失败: {{ chartError }}</span>
    </div>
    <div v-else class="chart-wrapper" :ref="(el) => chartEl = el"></div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, ScatterChart, RadarChart, FunnelChart, GaugeChart, HeatmapChart,
  CandlestickChart, SankeyChart, TreemapChart, SunburstChart, GraphChart, BoxplotChart, ThemeRiverChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent, GridComponent,
  ToolboxComponent, DataZoomComponent, MarkLineComponent, MarkPointComponent,
  VisualMapComponent, VisualMapContinuousComponent, VisualMapPiecewiseComponent,
  GeoComponent, DatasetComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  BarChart, LineChart, PieChart, ScatterChart, RadarChart, FunnelChart, GaugeChart, HeatmapChart,
  CandlestickChart, SankeyChart, TreemapChart, SunburstChart, GraphChart, BoxplotChart, ThemeRiverChart,
  TitleComponent, TooltipComponent, LegendComponent, GridComponent,
  ToolboxComponent, DataZoomComponent, MarkLineComponent, MarkPointComponent,
  VisualMapComponent, VisualMapContinuousComponent, VisualMapPiecewiseComponent,
  GeoComponent, DatasetComponent,
  CanvasRenderer
])

const props = defineProps({
  title: String,
  option: { type: [Object, String], required: true }
})

const chartEl = ref(null)
const chartError = ref(null)
let chartInstance = null

function parseOption(raw) {
  let opt = raw
  let depth = 0
  while (typeof opt === 'string' && depth < 3) {
    try {
      opt = JSON.parse(opt)
      depth++
    } catch (e) {
      chartError.value = '配置解析失败: ' + (e.message || '')
      return null
    }
  }
  if (typeof opt === 'string') {
    chartError.value = '配置格式异常'
    return null
  }
  return opt
}

function ensureToolbox(opt) {
  if (!opt.toolbox) {
    opt = { ...opt, toolbox: { feature: { saveAsImage: { title: '保存图片' } }, right: 10, top: 5 } }
  }
  return opt
}

function renderChart() {
  if (!chartEl.value) return
  chartError.value = null
  const parsed = parseOption(props.option)
  if (!parsed) return

  const opt = ensureToolbox(parsed)
  try {
    if (!chartInstance) {
      chartInstance = echarts.init(chartEl.value)
    }
    chartInstance.setOption(opt, true)
  } catch (e) {
    chartError.value = e.message || '渲染异常'
    chartInstance?.dispose()
    chartInstance = null
  }
}

onMounted(() => {
  renderChart()
  if (!chartEl.value) return
  const ro = new ResizeObserver(() => chartInstance?.resize())
  ro.observe(chartEl.value)
  onUnmounted(() => {
    ro.disconnect()
    chartInstance?.dispose()
    chartInstance = null
  })
})

watch(() => props.option, renderChart, { deep: true })
</script>

<style scoped>
.chart-container {
  background: var(--surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: border-color var(--transition-base), box-shadow var(--transition-base);
}
.chart-container:hover {
  border-color: var(--brand-primary-light);
  box-shadow: var(--shadow-sm);
}
.chart-title {
  padding: var(--space-md) var(--space-lg) var(--space-xs);
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--text-primary);
  letter-spacing: -0.005em;
  border-bottom: 1px solid var(--border-lighter);
}
.chart-wrapper {
  width: 100%;
  height: 360px;
  min-height: 300px;
  padding: var(--space-sm) var(--space-xs);
}
.chart-error {
  padding: var(--space-lg) var(--space-md);
  text-align: center;
  color: var(--color-warning);
  font-size: var(--font-md);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  background: var(--color-warning-light);
  margin: var(--space-md);
  border-radius: var(--radius-md);
  border-left: 3px solid var(--color-warning);
}
.chart-error-icon { font-size: var(--font-xl); }
</style>
