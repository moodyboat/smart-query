<template>
  <div class="metrics-dashboard">
    <div v-if="warnings.length" class="metric-warnings">
      <el-alert
        v-for="warning in warnings"
        :key="warning"
        :title="warning"
        type="warning"
        :closable="false"
        show-icon
      />
    </div>

    <div v-if="summaryCards.length" class="summary-strip">
      <div v-for="item in summaryCards" :key="item.key" class="summary-card">
        <span class="summary-label">{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </div>
    </div>

    <div v-if="charts.length" class="chart-grid">
      <EChartsRenderer
        v-for="chart in charts"
        :key="chart.id"
        :title="chart.title"
        :option="chart.option"
      />
    </div>
    <el-empty v-else description="暂无可视化指标" :image-size="72" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import EChartsRenderer from '../EChartsRenderer.vue'
import { formatMetricName, formatMetricValue } from '../../composables/useModelDetail'

const props = defineProps({
  metrics: { type: [Object, String], default: () => ({}) },
  validation: { type: [Object, String], default: () => ({}) },
  featureImportance: { type: [Object, String], default: () => ({}) }
})

function parse(raw, fallback = {}) {
  if (!raw) return fallback
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) } catch { return fallback }
}

const metricData = computed(() => parse(props.metrics))
const validationData = computed(() => parse(props.validation))
const importanceData = computed(() => parse(props.featureImportance))

const warnings = computed(() => [
  metricData.value.overfitting_warning,
  metricData.value.sample_warning,
  metricData.value.imbalance_warning,
  metricData.value.probability_metric_warning
].filter(Boolean))

const summaryCards = computed(() => {
  const preferred = [
    'test_accuracy', 'test_balanced_accuracy', 'test_f1_macro', 'test_r2',
    'test_rmse', 'roc_auc', 'pr_auc', 'ks', 'lift_at_10pct',
    'silhouette_score', 'overfitting_gap'
  ]
  return preferred
    .filter(key => typeof metricData.value[key] === 'number')
    .slice(0, 6)
    .map(key => ({ key, label: formatMetricName(key), value: formatMetricValue(key, metricData.value[key]) }))
})

const charts = computed(() => {
  const result = []
  const metrics = metricData.value
  const validation = validationData.value

  const qualityKeys = [
    'test_accuracy', 'test_balanced_accuracy', 'test_precision_macro',
    'test_recall_macro', 'test_f1_macro', 'roc_auc', 'pr_auc', 'risk_recall'
  ].filter(key => typeof metrics[key] === 'number')
  if (qualityKeys.length) {
    result.push({
      id: 'quality', title: '模型质量对比',
      option: barOption(qualityKeys.map(formatMetricName), qualityKeys.map(key => metrics[key]), true)
    })
  }

  const trainTestPairs = [
    ['准确率', metrics.train_accuracy, metrics.test_accuracy],
    ['R²', metrics.train_r2, metrics.test_r2]
  ].filter(([, train, test]) => typeof train === 'number' && typeof test === 'number')
  if (trainTestPairs.length) {
    result.push({
      id: 'generalization', title: '训练集 / 测试集泛化对比',
      option: groupedBarOption(
        trainTestPairs.map(item => item[0]),
        trainTestPairs.map(item => item[1]),
        trainTestPairs.map(item => item[2])
      )
    })
  }

  if (Array.isArray(metrics.confusion_matrix) && metrics.confusion_matrix.length) {
    const labels = Array.isArray(metrics.class_labels)
      ? metrics.class_labels.map(String)
      : metrics.confusion_matrix.map((_, index) => `类别 ${index}`)
    const data = []
    metrics.confusion_matrix.forEach((row, actual) => {
      row.forEach((value, predicted) => data.push([predicted, actual, value]))
    })
    result.push({
      id: 'confusion', title: '混淆矩阵',
      option: {
        tooltip: { position: 'top', formatter: p => `实际 ${labels[p.data[1]]}<br/>预测 ${labels[p.data[0]]}<br/>样本 ${p.data[2]}` },
        grid: { left: 70, right: 30, top: 30, bottom: 55 },
        xAxis: { type: 'category', name: '预测类别', data: labels },
        yAxis: { type: 'category', name: '实际类别', data: labels },
        visualMap: { min: 0, max: Math.max(1, ...data.map(item => Number(item[2]) || 0)), calculable: true, orient: 'horizontal', left: 'center', bottom: 0 },
        series: [{ type: 'heatmap', data, label: { show: true }, emphasis: { itemStyle: { shadowBlur: 8 } } }]
      }
    })
  }

  if (metrics.per_class && typeof metrics.per_class === 'object') {
    const entries = Object.entries(metrics.per_class)
    result.push({
      id: 'per-class', title: '分类别表现',
      option: {
        tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
        grid: { left: 45, right: 20, top: 25, bottom: 55 },
        xAxis: { type: 'category', data: entries.map(([label]) => label) },
        yAxis: { type: 'value', min: 0, max: 1, axisLabel: { formatter: value => `${Math.round(value * 100)}%` } },
        series: ['precision', 'recall', 'f1'].map(key => ({
          name: formatMetricName(key), type: 'bar',
          data: entries.map(([, value]) => Number(value?.[key] || 0))
        }))
      }
    })
  }

  const windows = Array.isArray(validation.rolling_windows) ? validation.rolling_windows : []
  if (windows.length) {
    result.push({
      id: 'validation-windows', title: '时间窗口验证趋势',
      option: {
        tooltip: { trigger: 'axis' },
        grid: { left: 45, right: 20, top: 25, bottom: 40 },
        xAxis: { type: 'category', data: windows.map(item => `Fold ${item.fold}`) },
        yAxis: { type: 'value', axisLabel: { formatter: value => `${(value * 100).toFixed(0)}%` } },
        series: [{ type: 'line', smooth: true, symbolSize: 8, areaStyle: {}, data: windows.map(item => item.score) }]
      }
    })
  } else {
    const cvKeys = ['cv_mean', 'cv_worst', 'cv_recent'].filter(key => typeof validation[key] === 'number')
    if (cvKeys.length) {
      result.push({
        id: 'cross-validation', title: '交叉验证稳定性',
        option: barOption(cvKeys.map(formatMetricName), cvKeys.map(key => validation[key]), true)
      })
    }
  }

  const importance = Object.entries(importanceData.value)
    .filter(([, value]) => typeof value === 'number')
    .sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]))
    .slice(0, 12)
    .reverse()
  if (importance.length) {
    result.push({
      id: 'importance', title: '特征贡献 Top 12',
      option: {
        tooltip: { trigger: 'axis' },
        grid: { left: 110, right: 25, top: 20, bottom: 30 },
        xAxis: { type: 'value' },
        yAxis: { type: 'category', data: importance.map(([name]) => name) },
        series: [{ type: 'bar', data: importance.map(([, value]) => value), itemStyle: { color: '#409eff' } }]
      }
    })
  }

  if (metrics.cluster_sizes && typeof metrics.cluster_sizes === 'object') {
    result.push({
      id: 'clusters', title: '聚类样本分布',
      option: {
        tooltip: { trigger: 'item' }, legend: { bottom: 0 },
        series: [{ type: 'pie', radius: ['38%', '68%'], data: Object.entries(metrics.cluster_sizes).map(([name, value]) => ({ name: `簇 ${name}`, value })) }]
      }
    })
  }
  return result
})

function barOption(labels, values, percentage = false) {
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 110, right: 25, top: 20, bottom: 30 },
    xAxis: { type: 'value', min: 0, max: percentage ? 1 : undefined, axisLabel: percentage ? { formatter: value => `${Math.round(value * 100)}%` } : {} },
    yAxis: { type: 'category', data: labels },
    series: [{ type: 'bar', data: values, label: { show: true, position: 'right', formatter: p => percentage ? `${(p.value * 100).toFixed(1)}%` : p.value } }]
  }
}

function groupedBarOption(labels, train, test) {
  return {
    tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
    grid: { left: 55, right: 20, top: 25, bottom: 55 },
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value', min: 0, max: 1, axisLabel: { formatter: value => `${Math.round(value * 100)}%` } },
    series: [
      { name: '训练集', type: 'bar', data: train },
      { name: '测试集', type: 'bar', data: test }
    ]
  }
}
</script>

<style scoped>
.metrics-dashboard { display: flex; flex-direction: column; gap: 12px; }
.metric-warnings { display: flex; flex-direction: column; gap: 6px; }
.summary-strip { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 8px; }
.summary-card { padding: 10px 12px; border: 1px solid var(--border-light); border-radius: var(--radius-md); background: var(--surface); }
.summary-card strong { display: block; margin-top: 4px; font-size: var(--font-xl); color: var(--brand-primary); }
.summary-label { font-size: var(--font-xs); color: var(--text-muted); }
.chart-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 12px; }
:deep(.chart-wrapper) { height: 300px; min-height: 260px; }
@media (max-width: 720px) { .chart-grid { grid-template-columns: 1fr; } }
</style>
