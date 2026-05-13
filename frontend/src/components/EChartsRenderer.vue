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

function renderChart() {
  if (!chartEl.value) return
  chartError.value = null
  const opt = parseOption(props.option)
  if (!opt) return

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
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}
.chart-title {
  padding: 10px 14px 0;
  font-size: 14px;
  font-weight: 600;
  color: #333;
}
.chart-wrapper {
  width: 100%;
  height: 360px;
  min-height: 300px;
}
.chart-error {
  padding: 20px 14px;
  text-align: center;
  color: #e6a23c;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: #fdf6ec;
  margin: 8px 12px;
  border-radius: 6px;
}
.chart-error-icon { font-size: 16px; }
</style>
