<template>
  <!-- Preview Results Drawer -->
  <el-drawer :model-value="showPreviewDrawer" title="试运行结果" size="520px" direction="rtl" :modal="false"
    @update:model-value="$emit('update:showPreviewDrawer', $event)">
    <div v-if="previewResult" class="preview-panel">
      <div class="preview-header">
        <el-tag :type="previewResult.status === 'success' ? 'success' : 'danger'" size="small">
          {{ previewResult.status === 'success' ? '成功' : '失败' }}
        </el-tag>
        <span class="preview-node-type">{{ nodeTitle(previewResult.nodeType) }}</span>
      </div>

      <!-- Error display -->
      <div v-if="previewResult.error" class="preview-error">
        <el-alert type="error" :closable="false" :title="previewResult.error" />
      </div>

      <!-- Data Source Preview -->
      <template v-if="previewResult.nodeType === 'data_source' && previewResult.status === 'success'">
        <div class="preview-stats">
          <div class="preview-stat"><span class="ps-value">{{ previewResult.rowCount }}</span><span class="ps-label">总行数</span></div>
          <div class="preview-stat"><span class="ps-value">{{ previewResult.columnCount }}</span><span class="ps-label">列数</span></div>
          <div class="preview-stat"><span class="ps-value">{{ previewResult.tableName }}</span><span class="ps-label">表名</span></div>
        </div>
        <div v-if="previewResult.nullSummary && Object.keys(previewResult.nullSummary).length" class="preview-section">
          <div class="preview-section-title">缺失值</div>
          <div v-for="(cnt, col) in previewResult.nullSummary" :key="col" class="preview-null-row">
            <span>{{ col }}</span><span>{{ cnt }} 个缺失</span>
          </div>
        </div>
        <div class="preview-section">
          <div class="preview-section-title">列信息</div>
          <div class="preview-table-wrap">
            <table class="preview-table">
              <thead><tr><th>列名</th><th>类型</th><th>缺失</th><th>示例</th></tr></thead>
              <tbody><tr v-for="col in previewResult.columns" :key="col.name"><td>{{ col.name }}</td><td>{{ col.dtype }}</td><td>{{ col.nulls }}</td><td>{{ col.sample }}</td></tr></tbody>
            </table>
          </div>
        </div>
        <div v-if="previewResult.sampleRows?.length" class="preview-section">
          <div class="preview-section-title">样本数据 (前 {{ previewResult.sampleRows.length }} 行)</div>
          <div class="preview-table-wrap">
            <table class="preview-table">
              <thead><tr><th v-for="col in Object.keys(previewResult.sampleRows[0])" :key="col">{{ col }}</th></tr></thead>
              <tbody><tr v-for="(row, ri) in previewResult.sampleRows.slice(0, 10)" :key="ri"><td v-for="col in Object.keys(previewResult.sampleRows[0])" :key="col">{{ row[col] }}</td></tr></tbody>
            </table>
          </div>
        </div>
      </template>

      <!-- Preprocessing / Fill Missing Preview -->
      <template v-if="(previewResult.nodeType === 'preprocessing' || previewResult.nodeType === 'fill_missing') && previewResult.status === 'success'">
        <div class="preview-stats">
          <div class="preview-stat"><span class="ps-value">{{ previewResult.beforeRows || '-' }}</span><span class="ps-label">处理前行数</span></div>
          <div class="preview-stat"><span class="ps-value">{{ previewResult.rowCount }}</span><span class="ps-label">处理后行数</span></div>
          <div class="preview-stat">
            <span class="ps-value">{{ previewResult.beforeRows && previewResult.rowCount ? (previewResult.beforeRows - previewResult.rowCount) : 0 }}</span>
            <span class="ps-label">删除行数</span>
          </div>
          <div class="preview-stat"><span class="ps-value">{{ previewResult.columnCount }}</span><span class="ps-label">列数</span></div>
        </div>

        <!-- Null summary with before/after -->
        <div v-if="previewResult.beforeNulls" class="preview-section">
          <div class="preview-section-title">缺失值概览</div>
          <div class="preview-null-grid">
            <template v-for="(cnt, col) in previewResult.beforeNulls" :key="col">
              <div v-if="cnt > 0" class="preview-null-row">
                <span class="pnr-col">{{ col }}</span>
                <span class="pnr-before">{{ cnt }} 个缺失</span>
                <span class="pnr-after" :class="{ resolved: !previewResult.remainingNulls?.[col] }">
                  {{ previewResult.remainingNulls?.[col] ?? 0 }} 个剩余
                </span>
              </div>
            </template>
            <div v-if="!Object.values(previewResult.beforeNulls).some(v => v > 0)" class="preview-null-clean">
              所有列均无缺失值
            </div>
          </div>
        </div>
        <div v-if="previewResult.nullComparison?.length" class="preview-section">
          <div class="preview-section-title">缺失值处理效果 (before → after)</div>
          <div v-for="nc in previewResult.nullComparison" :key="nc.name" class="preview-null-row">
            <span>{{ nc.name }}</span>
            <span :style="{ color: nc.after === 0 ? 'var(--color-success)' : 'var(--color-warning)' }">{{ nc.before }} → {{ nc.after }}</span>
          </div>
        </div>

        <!-- Column strategies applied -->
        <div v-if="previewResult.columnStrategies && Object.keys(previewResult.columnStrategies).length" class="preview-section">
          <div class="preview-section-title">逐列策略</div>
          <div class="preview-tags">
            <el-tag v-for="(strategy, col) in previewResult.columnStrategies" :key="col" size="small" type="info" class="feat-tag">
              {{ col }}: {{ { fill_mean: '均值', fill_median: '中位数', fill_mode: '众数', drop: '删除行', none: '不处理' }[strategy] || strategy }}
            </el-tag>
          </div>
        </div>

        <!-- Column types after processing -->
        <div v-if="previewResult.columns?.length" class="preview-section">
          <div class="preview-section-title">处理后列信息</div>
          <div class="preview-table-wrap" style="max-height: 200px; overflow-y: auto;">
            <table class="preview-table">
              <thead><tr><th>列名</th><th>类型</th><th>缺失</th><th>缺失率</th></tr></thead>
              <tbody>
                <tr v-for="col in previewResult.columns" :key="col.name">
                  <td>{{ col.name }}</td>
                  <td>{{ col.dtype }}</td>
                  <td>{{ col.nulls }}</td>
                  <td :style="{ color: col.nullPct > 0 ? 'var(--color-warning)' : 'inherit' }">{{ col.nullPct }}%</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="previewResult.sampleRows?.length" class="preview-section">
          <div class="preview-section-title">处理后样本 (前10行)</div>
          <div class="preview-table-wrap">
            <table class="preview-table">
              <thead><tr><th v-for="col in Object.keys(previewResult.sampleRows[0])" :key="col">{{ col }}</th></tr></thead>
              <tbody><tr v-for="(row, ri) in previewResult.sampleRows.slice(0, 10)" :key="ri"><td v-for="col in Object.keys(previewResult.sampleRows[0])" :key="col">{{ row[col] }}</td></tr></tbody>
            </table>
          </div>
        </div>
      </template>

      <!-- Feature Engineering Preview -->
      <template v-if="previewResult.nodeType === 'feature_engineering' && previewResult.status === 'success'">
        <div class="preview-stats">
          <div class="preview-stat"><span class="ps-value">{{ previewResult.featureCount }}</span><span class="ps-label">特征数</span></div>
          <div class="preview-stat"><span class="ps-value">{{ previewResult.targetColumn || '未设置' }}</span><span class="ps-label">目标列</span></div>
          <div class="preview-stat"><span class="ps-value">{{ previewResult.sampleShape?.[0] || 0 }} × {{ previewResult.sampleShape?.[1] || 0 }}</span><span class="ps-label">矩阵形状</span></div>
        </div>

        <!-- Tab toggle: Stats | Charts -->
        <div class="preview-tab-toggle">
          <button :class="['ptab-btn', { active: featTab === 'stats' }]" @click="featTab = 'stats'">统计</button>
          <button :class="['ptab-btn', { active: featTab === 'charts' }]" @click="featTab = 'charts'">图表</button>
        </div>

        <!-- Stats tab -->
        <template v-if="featTab === 'stats'">
          <div v-if="previewResult.targetDistribution && Object.keys(previewResult.targetDistribution).length" class="preview-section">
            <div class="preview-section-title">目标列分布</div>
            <div v-for="(cnt, val) in previewResult.targetDistribution" :key="val" class="preview-null-row">
              <span>{{ val }}</span><span>{{ cnt }} 条</span>
            </div>
          </div>
          <div v-if="previewResult.correlations && Object.keys(previewResult.correlations).length" class="preview-section">
            <div class="preview-section-title">与目标列的相关性</div>
            <div class="preview-tags">
              <el-tag v-for="col in Object.keys(previewResult.correlations)" :key="col" size="small"
                :type="Math.abs(previewResult.correlations[col]) > 0.3 ? 'warning' : 'info'" class="feat-tag">
                {{ col }}: {{ previewResult.correlations[col] > 0 ? '+' : '' }}{{ previewResult.correlations[col] }}
              </el-tag>
            </div>
          </div>
          <div v-if="previewResult.featureStats?.length" class="preview-section">
            <div class="preview-section-title">特征分析</div>
            <div class="feat-stats-list">
              <div v-for="fs in previewResult.featureStats" :key="fs.name" class="feat-stat-row" :class="{ 'has-warning': fs.nullPct > 10 }">
                <div class="feat-stat-header">
                  <span class="feat-stat-name">{{ fs.name }}</span>
                  <span class="feat-stat-dtype">{{ fs.dtype }}</span>
                  <span :class="['feat-stat-null', fs.nullPct > 0 ? 'has-nulls' : '']">{{ fs.nullPct }}% 缺失</span>
                  <span v-if="previewResult.correlations?.[fs.name] != null" class="feat-stat-corr"
                    :style="{ color: Math.abs(previewResult.correlations[fs.name]) > 0.3 ? 'var(--color-warning)' : 'var(--color-info)' }">
                    r={{ previewResult.correlations[fs.name] > 0 ? '+' : '' }}{{ previewResult.correlations[fs.name] }}
                  </span>
                </div>
                <div v-if="fs.mean != null" class="feat-stat-detail">
                  均值: {{ fs.mean }} · 标准差: {{ fs.std }} · 范围: [{{ fs.min }}, {{ fs.max }}]
                </div>
                <div v-if="fs.topValues" class="feat-stat-detail">
                  Top: <span v-for="(cnt, val, i) in fs.topValues" :key="val">{{ i > 0 ? ', ' : '' }}{{ val }}({{ cnt }})</span>
                  <span v-if="fs.unique"> · {{ fs.unique }} 个唯一值</span>
                </div>
              </div>
            </div>
          </div>
          <div class="preview-section">
            <div class="preview-section-title">特征列</div>
            <div class="preview-tags">
              <el-tag v-for="col in (previewResult.featureColumns || []).slice(0, 20)" :key="col" size="small" type="info" class="feat-tag">{{ col }}</el-tag>
              <span v-if="(previewResult.featureColumns || []).length > 20" class="feat-more">+{{ previewResult.featureColumns.length - 20 }} 个</span>
            </div>
          </div>
        </template>

        <!-- Charts tab -->
        <template v-if="featTab === 'charts'">
          <div v-if="!featChartData.hasChartData" class="preview-charts-empty">
            暂无图表数据，请先试运行特征工程节点
          </div>
          <template v-else>
            <div v-if="featChartData.importanceOption" class="preview-chart-section">
              <EChartsRenderer :option="featChartData.importanceOption" style="height:280px" />
            </div>
            <div v-if="featChartData.correlationOption" class="preview-chart-section">
              <EChartsRenderer :option="featChartData.correlationOption" style="height:340px" />
            </div>
            <div v-if="featChartData.histogramOptions.length" class="preview-chart-section">
              <div class="preview-section-title">特征分布直方图</div>
              <div class="histogram-grid">
                <div v-for="h in featChartData.histogramOptions" :key="h.name" class="histogram-item">
                  <EChartsRenderer :option="h.option" style="height:180px" />
                </div>
              </div>
            </div>
          </template>
        </template>

        <div v-if="previewResult.sampleRows?.length && featTab === 'stats'" class="preview-section">
          <div class="preview-section-title">特征矩阵样本 (前5行)</div>
          <div class="preview-table-wrap">
            <table class="preview-table">
              <thead><tr><th v-for="col in Object.keys(previewResult.sampleRows[0])" :key="col">{{ col }}</th></tr></thead>
              <tbody><tr v-for="(row, ri) in previewResult.sampleRows" :key="ri"><td v-for="col in Object.keys(previewResult.sampleRows[0])" :key="col">{{ typeof row[col] === 'number' ? row[col].toFixed(2) : row[col] }}</td></tr></tbody>
            </table>
          </div>
        </div>
      </template>

      <!-- Training/Evaluation Preview -->
      <template v-if="(previewResult.nodeType === 'training' || previewResult.nodeType === 'evaluation') && previewResult.status === 'success'">
        <div class="preview-stats">
          <div class="preview-stat"><span class="ps-value">{{ previewResult.trainSize }}</span><span class="ps-label">训练集</span></div>
          <div class="preview-stat"><span class="ps-value">{{ previewResult.testSize }}</span><span class="ps-label">测试集</span></div>
          <div class="preview-stat"><span class="ps-value">{{ previewResult.featureCount }}</span><span class="ps-label">特征数</span></div>
        </div>
        <div v-if="previewResult.metrics" class="preview-section">
          <div class="preview-section-title">模型指标</div>
          <div class="preview-metrics-grid">
            <div v-for="(val, key) in previewResult.metrics" :key="key" class="preview-metric-card">
              <span class="pm-value">{{ typeof val === 'number' ? (val < 10 ? val.toFixed(4) : (val * 100).toFixed(1) + '%') : val }}</span>
              <span class="pm-label">{{ metricLabel(key) }}</span>
            </div>
          </div>
        </div>
        <div v-if="trainImportanceOption" class="preview-section">
          <div class="preview-section-title">特征重要性 Top 10</div>
          <EChartsRenderer :option="trainImportanceOption" style="height:260px" />
        </div>
        <div v-else-if="previewResult.featureImportance" class="preview-section">
          <div class="preview-section-title">特征重要性 Top 10</div>
          <div v-for="(f, i) in topPreviewFeatures" :key="i" class="preview-fi-row">
            <span class="fi-name">{{ f.name }}</span>
            <div class="fi-bar-track"><div class="fi-bar-fill" :style="{ width: (f.value / topPreviewFeatures[0].value * 100) + '%' }"></div></div>
            <span class="fi-val">{{ (f.value * 100).toFixed(1) }}%</span>
          </div>
        </div>
      </template>

      <!-- Output Preview -->
      <template v-if="previewResult.nodeType === 'output' && previewResult.status === 'success'">
        <div class="preview-stats">
          <div class="preview-stat"><span class="ps-value">{{ previewResult.totalRows }}</span><span class="ps-label">总输出行数</span></div>
          <div class="preview-stat"><span class="ps-value">{{ previewResult.outputTable || '未配置' }}</span><span class="ps-label">目标表</span></div>
        </div>
        <div v-if="previewResult.predictionDistribution" class="preview-section">
          <div class="preview-section-title">预测分布</div>
          <div v-for="(cnt, val) in previewResult.predictionDistribution" :key="val" class="preview-null-row">
            <span>预测值 {{ val }}</span><span>{{ cnt }} 条</span>
          </div>
        </div>
        <div v-if="previewResult.sampleRows?.length" class="preview-section">
          <div class="preview-section-title">输出样本 (前10行)</div>
          <div class="preview-table-wrap">
            <table class="preview-table">
              <thead><tr><th v-for="col in (previewResult.columns || []).slice(0, 10)" :key="col">{{ col }}</th></tr></thead>
              <tbody><tr v-for="(row, ri) in previewResult.sampleRows.slice(0, 10)" :key="ri"><td v-for="col in (previewResult.columns || []).slice(0, 10)" :key="col">{{ row[col] }}</td></tr></tbody>
            </table>
          </div>
        </div>
      </template>
    </div>
  </el-drawer>

  <!-- Script Viewer Drawer -->
  <el-drawer :model-value="showScriptDrawer" title="Python 脚本" size="600px" direction="rtl" :modal="false"
    @update:model-value="$emit('update:showScriptDrawer', $event)">
    <div v-if="scriptLoading" style="text-align:center;padding:40px">
      <p style="color:var(--text-muted)">加载脚本中...</p>
    </div>
    <div v-else-if="scriptContent" class="script-viewer">
      <div class="script-toolbar">
        <el-button size="small" @click="$emit('copyScript')">复制代码</el-button>
      </div>
      <pre class="script-code"><code>{{ scriptContent }}</code></pre>
    </div>
    <div v-else style="text-align:center;padding:40px;color:var(--text-muted)">
      请先配置该节点以生成脚本
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, ref } from 'vue'
import { METRIC_NAMES } from '../../api'
import { useFeatureCharts } from '../../composables/useFeatureCharts'
import EChartsRenderer from '../EChartsRenderer.vue'

const featTab = ref('stats')

const props = defineProps({
  previewResult: { type: Object, default: null },
  previewingNodeId: { type: String, default: null },
  showPreviewDrawer: { type: Boolean, default: false },
  showScriptDrawer: { type: Boolean, default: false },
  scriptContent: { type: String, default: '' },
  scriptLoading: { type: Boolean, default: false }
})

defineEmits([
  'update:showPreviewDrawer',
  'update:showScriptDrawer',
  'copyScript'
])

const NODE_TYPE_LABELS = {
  data_source: '数据接入',
  preprocessing: '数据预处理',
  fill_missing: '填充缺失值',
  feature_engineering: '特征工程',
  training: '模型训练',
  evaluation: '模型评估',
  output: '输出写入'
}

const PERCENT_METRICS = new Set([
  'accuracy', 'precision', 'recall', 'f1',
  'train_accuracy', 'test_accuracy', 'train_f1', 'test_f1',
  'cv_mean', 'overfitting_gap'
])

function nodeTitle(type) {
  return NODE_TYPE_LABELS[type] || type
}

function metricLabel(key) {
  return METRIC_NAMES[key] || key
}

function parseMetrics(json) {
  try {
    return typeof json === 'string' ? JSON.parse(json) : json || {}
  } catch {
    return {}
  }
}

function formatMetricValue(key, val) {
  if (PERCENT_METRICS.has(key)) {
    return (val * 100).toFixed(1) + '%'
  }
  if (key === 'silhouette_score' || key === 'silhouette') {
    return val.toFixed(4)
  }
  return val < 10 ? val.toFixed(4) : val.toFixed(2)
}

const topPreviewFeatures = computed(() => {
  if (!props.previewResult?.featureImportance) return []
  const fi = props.previewResult.featureImportance
  return Object.entries(fi)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10)
    .map(([name, value]) => ({ name, value }))
})

const featChartData = useFeatureCharts(computed(() => props.previewResult))

const trainImportanceOption = computed(() => {
  const fi = props.previewResult?.featureImportance
  if (!fi || typeof fi !== 'object') return null
  const sorted = Object.entries(fi)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10)
  if (!sorted.length) return null
  const maxVal = sorted[0][1]
  return {
    title: { text: '特征重要性', left: 'center', textStyle: { fontSize: 13, fontWeight: 600 } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 90, right: 30, top: 36, bottom: 16 },
    xAxis: { type: 'value', max: maxVal * 100, axisLabel: { formatter: v => v.toFixed(0) + '%' } },
    yAxis: { type: 'category', data: sorted.map(s => s[0]).reverse(), axisLabel: { fontSize: 11 } },
    series: [{
      type: 'bar',
      data: sorted.map(s => (s[1] * 100).toFixed(2)).reverse(),
      itemStyle: {
        color: (params) => {
          const ratio = params.value / (maxVal * 100)
          return ratio > 0.7 ? '#5470c6' : ratio > 0.4 ? '#91cc75' : '#fac858'
        },
        borderRadius: [0, 3, 3, 0]
      },
      barMaxWidth: 14
    }]
  }
})
</script>

<style scoped>
/* Preview Panel */
.preview-panel {
  padding: 0 var(--space-sm);
}

.preview-header {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-lg);
}

.preview-node-type {
  font-weight: 600;
  font-size: var(--font-base);
}

.preview-error {
  margin-bottom: var(--space-lg);
}

.preview-stats {
  display: flex;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
  flex-wrap: wrap;
}

.preview-stat {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-sm) var(--space-lg);
  text-align: center;
  min-width: 80px;
}

.ps-value {
  display: block;
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--text-primary);
}

.ps-label {
  display: block;
  font-size: var(--font-xs);
  color: var(--text-muted);
  margin-top: 2px;
}

.preview-section {
  margin-bottom: var(--space-lg);
}

.preview-section-title {
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: var(--space-sm);
  padding-bottom: var(--space-xs);
  border-bottom: 1px solid var(--border);
}

.preview-null-row {
  display: flex;
  justify-content: space-between;
  font-size: var(--font-sm);
  padding: var(--space-xs) 0;
  color: var(--text-secondary);
}

.preview-null-grid {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.preview-null-grid .preview-null-row {
  gap: 8px;
  align-items: center;
}

.preview-null-grid .pnr-col {
  flex: 1;
  font-weight: 500;
}

.preview-null-grid .pnr-before {
  color: var(--color-warning);
  font-size: 12px;
}

.preview-null-grid .pnr-after {
  font-size: 12px;
}

.preview-null-grid .pnr-after.resolved {
  color: var(--color-success);
}

.preview-null-clean {
  font-size: 12px;
  color: var(--color-success);
  padding: 4px 0;
}

.preview-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.feat-tag {
  font-size: var(--font-xs);
}

.feat-more {
  font-size: var(--font-xs);
  color: var(--text-muted);
  line-height: var(--space-xl);
}

.preview-table-wrap {
  overflow-x: auto;
}

.preview-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--font-xs);
}

.preview-table th {
  background: var(--surface);
  padding: var(--space-xs) var(--space-sm);
  text-align: left;
  font-weight: 600;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.preview-table td {
  padding: var(--space-xs) var(--space-sm);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
  gap: var(--space-sm);
}

.preview-metric-card {
  background: var(--el-color-success-light-9);
  border-radius: var(--radius-lg);
  padding: var(--space-sm);
  text-align: center;
}

.pm-value {
  display: block;
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--el-color-success);
}

.pm-label {
  display: block;
  font-size: var(--font-xs);
  color: var(--text-muted);
  margin-top: 2px;
}

.preview-fi-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-xs);
}

.fi-name {
  width: 90px;
  font-size: var(--font-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fi-bar-track {
  flex: 1;
  height: var(--space-xs);
  background: var(--border);
  border-radius: 3px;
  overflow: hidden;
}

.fi-bar-fill {
  height: 100%;
  background: var(--primary);
  border-radius: 3px;
}

.fi-val {
  width: 40px;
  font-size: var(--font-xs);
  color: var(--text-muted);
  text-align: right;
}

/* Feature stats in preview */
.feat-stats-list {
  max-height: 300px;
  overflow-y: auto;
}

.feat-stat-row {
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
}

.feat-stat-row.has-warning {
  background: var(--el-color-warning-light-9);
}

.feat-stat-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.feat-stat-name {
  font-weight: 500;
  flex: 1;
}

.feat-stat-dtype {
  color: var(--text-muted);
  font-size: 11px;
}

.feat-stat-null {
  font-size: 11px;
  color: var(--el-color-success);
}

.feat-stat-null.has-nulls {
  color: var(--el-color-warning);
}

.feat-stat-corr {
  margin-left: 8px;
  font-size: 11px;
  font-weight: 500;
}

.feat-stat-detail {
  color: var(--text-secondary);
  margin-top: 2px;
  font-size: 11px;
}

/* Script viewer */
.script-viewer {
  padding: 0;
}

.script-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.script-code {
  background: var(--code-bg);
  color: var(--code-fg);
  padding: 16px;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre;
  margin: 0;
  max-height: calc(100vh - 120px);
  overflow-y: auto;
}

.script-code code {
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
}

/* Tab toggle */
.preview-tab-toggle {
  display: flex;
  gap: 0;
  margin-bottom: var(--space-md);
  border-bottom: 2px solid var(--border);
}

.ptab-btn {
  padding: 6px 16px;
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--text-muted);
  background: none;
  border: none;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  transition: all 0.2s;
}

.ptab-btn.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}

.ptab-btn:hover:not(.active) {
  color: var(--text-secondary);
}

/* Charts */
.preview-chart-section {
  margin-bottom: var(--space-md);
}

.preview-charts-empty {
  text-align: center;
  padding: 24px;
  color: var(--text-muted);
  font-size: var(--font-sm);
}

.histogram-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-sm);
}

.histogram-item {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}
</style>
