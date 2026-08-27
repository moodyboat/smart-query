import { ref, computed } from 'vue'
import { fetchModelExecutions, fetchMiningModel, fetchMiningPipeline, syncModelPipeline } from '../api'
import { NODE_TYPES, NODE_TYPE_LABELS } from '../constants'

export function pipelineNodeIcon(type) {
  return { data_source: '\u{1F4E5}', preprocessing: '\u{1F527}', fill_missing: '\u{1FA79}', feature_engineering: '⚙️', training: '\u{1F9E0}', evaluation: '\u{1F4CA}', output: '\u{1F4BE}' }[type] || '\u{1F4E6}'
}

export function pipelineNodeTitle(node, algorithmLabel) {
  if (node.type === NODE_TYPES.TRAINING && node.config?.algorithm) return algorithmLabel(node.config.algorithm)
  return NODE_TYPE_LABELS[node.type] || node.type
}

export function pipelineNodeSummary(node) {
  const c = node.config || {}
  switch (node.type) {
    case NODE_TYPES.DATA_SOURCE: return c.table || '未配置'
    case NODE_TYPES.PREPROCESSING: return [c.handleMissing !== 'none' ? '处理缺失' : '', c.encoding !== 'none' ? '编码' : '', c.scaling !== 'none' ? '缩放' : ''].filter(Boolean).join('+') || '默认'
    case NODE_TYPES.FILL_MISSING: return { auto: '自动', mean: '均值', median: '中位数', mode: '众数' }[c.strategy] || '自动'
    case NODE_TYPES.FEATURE_ENGINEERING: {
      const fc = c.featureColumns ? (typeof c.featureColumns === 'string' ? JSON.parse(c.featureColumns) : c.featureColumns) : []
      return fc.length ? `${fc.length}特征` : '未配置'
    }
    case NODE_TYPES.TRAINING: return c.hyperparams ? Object.entries(c.hyperparams).slice(0, 2).map(([k, v]) => `${k}=${v}`).join(', ') : ''
    case NODE_TYPES.EVALUATION: {
      const vm = c.validationMode
      if (vm === 'temporal') return `时间外验证 (${c.temporalColumn || '?'})`
      if (vm === 'cv') return `${c.cvFold || 5}-Fold CV`
      if (vm === 'oos') return `OOS ${c.cvFold || 5}-Fold + ${c.testSize || 20}%测试`
      return `${c.testSize || 20}%测试`
    }
    case NODE_TYPES.OUTPUT: return c.table || '未配置'
    default: return ''
  }
}

export function isNodeConfigured(node) {
  const c = node.config || {}
  switch (node.type) {
    case NODE_TYPES.DATA_SOURCE: return !!c.table
    case NODE_TYPES.FEATURE_ENGINEERING: {
      try {
        const fc = c.featureColumns ? (typeof c.featureColumns === 'string' ? JSON.parse(c.featureColumns) : c.featureColumns) : []
        return fc.length > 0
      } catch { return false }
    }
    case NODE_TYPES.TRAINING: return !!c.algorithm
    case NODE_TYPES.OUTPUT: return !!c.table
    default: return true
  }
}

export function parsedMetrics(json) {
  try {
    const raw = typeof json === 'string' ? JSON.parse(json) : json || {}
    const result = { ...raw }
    for (const [k, v] of Object.entries(raw)) {
      if (k.startsWith('test_')) {
        const canonical = k.slice(5)
        if (!(canonical in result)) result[canonical] = v
      }
    }
    return result
  } catch {
    return {}
  }
}

const METRIC_NAMES = {
  accuracy: '准确率', precision: '精确率', recall: '召回率', f1: 'F1',
  r2: 'R²', rmse: 'RMSE', mse: 'MSE', mae: 'MAE',
  train_accuracy: '训练准确率', test_accuracy: '测试准确率',
  train_f1: '训练F1', test_f1: '测试F1',
  train_precision: '训练精确率', test_precision: '测试精确率',
  train_recall: '训练召回率', test_recall: '测试召回率',
  train_r2: '训练R²', test_r2: '测试R²',
  cv_mean: 'CV均值', cv_std: 'CV标准差',
  test_balanced_accuracy: '平衡准确率', test_precision_macro: '宏平均精确率',
  test_recall_macro: '宏平均召回率', test_f1_macro: '宏平均 F1',
  roc_auc: 'ROC-AUC', pr_auc: 'PR-AUC', ks: 'KS', lift_at_10pct: 'Top10% Lift',
  risk_recall: '风险类召回率', decision_threshold: '决策阈值',
  overfitting_gap: '过拟合差距', confusion_matrix: '混淆矩阵',
  class_labels: '类别标签', silhouette_score: '轮廓系数',
  inertia: '惯性', n_clusters: '聚类数',
  overfitting_warning: '过拟合警告'
}

export function formatMetricName(key) { return METRIC_NAMES[key] || key }

export function formatMetricValue(key, val) {
  if (val == null) return '-'
  if (typeof val !== 'number') return String(val)
  if (key === 'overfitting_gap') return (val * 100).toFixed(1) + '%'
  if (key === 'cv_mean' || key === 'cv_std') return (val * 100).toFixed(1) + '%'
  const pctKeys = ['accuracy', 'precision', 'recall', 'f1', 'r2',
    'train_accuracy', 'test_accuracy', 'train_f1', 'test_f1',
    'train_precision', 'test_precision', 'train_recall', 'test_recall',
    'train_r2', 'test_r2', 'silhouette_score', 'test_balanced_accuracy',
    'test_precision_macro', 'test_recall_macro', 'test_f1_macro',
    'roc_auc', 'pr_auc', 'ks', 'risk_recall']
  return pctKeys.includes(key) ? (val * 100).toFixed(1) + '%' : Number(val).toFixed(4)
}

export function metricQuality(val, modelType, key) {
  if (val == null) return 'neutral'
  if (modelType === 'clustering') return 'neutral'
  if (modelType === 'regression') {
    if (key === 'rmse' || key === 'mse' || key === 'mae') return 'neutral'
    if (val >= 0.8) return 'good'
    if (val >= 0.5) return 'moderate'
    return 'poor'
  }
  if (val >= 0.9) return 'good'
  if (val >= 0.7) return 'moderate'
  return 'poor'
}

export function overfittingWarning(model) {
  const m = parsedMetrics(model.metrics)
  const gap = m.overfitting_gap
  if (gap == null) return null
  if (gap >= 0.15) return `过拟合警告 (差距 ${(gap * 100).toFixed(1)}%): 训练表现远好于测试，建议减少特征或增加正则化`
  if (gap >= 0.05) return `轻微过拟合迹象 (差距 ${(gap * 100).toFixed(1)}%)，可考虑优化`
  return null
}

export function useModelDetail(mining) {
  const showDetail = ref(false)
  const detailModel = ref(null)
  const executions = ref([])
  const detailPipelineNodes = ref([])
  const expandedNodeId = ref(null)
  const loadingExecutions = ref(false)
  const syncingNode = ref(false)

  const sortedImportance = computed(() => {
    if (!detailModel.value?.featureImportance) return {}
    const parsed = parseJson(detailModel.value.featureImportance, {})
    return Object.entries(parsed).sort((a, b) => b[1] - a[1]).slice(0, 10).reduce((acc, [k, v]) => { acc[k] = v; return acc }, {})
  })

  const maxImportance = computed(() => {
    const vals = Object.values(sortedImportance.value)
    return vals.length ? Math.max(...vals) : 1
  })

  async function selectModel(model) {
    mining.selectModel(model.id)
    detailModel.value = model
    showDetail.value = true
    detailPipelineNodes.value = []
    loadingExecutions.value = true
    try {
      executions.value = await fetchModelExecutions(model.id) || []
    } catch {
      executions.value = []
    } finally {
      loadingExecutions.value = false
    }
    if (model.pipelineId) {
      try {
        const pipeline = await fetchMiningPipeline(model.pipelineId)
        if (pipeline?.nodes) {
          detailPipelineNodes.value = typeof pipeline.nodes === 'string'
            ? JSON.parse(pipeline.nodes) : pipeline.nodes
        }
      } catch { /* pipeline not found */ }
    }
  }

  function onNodeParamUpdate({ nodeId, config }) {
    const idx = detailPipelineNodes.value.findIndex(n => n.id === nodeId)
    if (idx >= 0) {
      detailPipelineNodes.value[idx] = { ...detailPipelineNodes.value[idx], config: { ...config } }
    }
  }

  async function syncNodeChanges() {
    if (!detailModel.value?.pipelineId) return
    syncingNode.value = true
    try {
      await syncModelPipeline(detailModel.value.id)
      const fresh = await fetchMiningModel(detailModel.value.id)
      if (fresh) {
        mining.updateModelInList(fresh)
        detailModel.value = fresh
      }
    } catch (e) {
      throw e
    } finally {
      syncingNode.value = false
    }
  }

  async function refreshDetail() {
    if (!detailModel.value) return
    setTimeout(async () => {
      try {
        const updated = await fetchMiningModel(detailModel.value.id)
        detailModel.value = updated
        mining.updateModelInList(updated)
      } catch {}
    }, 1000)
  }

  return {
    showDetail, detailModel, executions, detailPipelineNodes, expandedNodeId,
    loadingExecutions, syncingNode, sortedImportance, maxImportance,
    selectModel, onNodeParamUpdate, syncNodeChanges, refreshDetail
  }
}

function parseJson(json, fallback) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || fallback } catch { return fallback }
}
