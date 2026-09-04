import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  trainMiningModel, publishMiningModel, offlineMiningModel,
  predictMiningModel, batchPredictMiningModel,
  updateModelSchedule, updateMiningModel,
  fetchMiningModel, fetchDataSourceTables,
  fetchModelPredictions, previewResultTable,
  deleteMiningModel, forceDeleteMiningModel, validateMiningModel
} from '../api'
import { MODEL_STATUS, TRAINING_SAFETY_TIMEOUT_MS } from '../constants'
import { parsedMetrics, formatMetricName } from './useModelDetail'
import { isGhostModel } from '../utils/modelGhost'

const STATUS_LABELS = {
  draft: '草稿', training: '训练中', trained: '已训练', failed: '训练失败',
  published: '已发布', offline: '已下线'
}

export function useModelActions(mining) {
  const trainingId = ref(null)
  const activeCleanup = { unwatch: null, timeoutId: null }

  // Publish dialog
  const showPublishDialog = ref(false)
  const publishModel_ref = ref(null)
  const publishLoading = ref(false)
  const publishConfig = ref({
    predictInputTable: '', predictInputFilter: '', predictResultTable: ''
  })
  const publishTableOptions = ref([])

  // Schedule dialog
  const showScheduleDialog = ref(false)
  const scheduleModel = ref(null)
  const scheduleCron = ref('0 6 * * *')
  const scheduleEnabled = ref(false)
  const scheduleMode = ref('train')
  const scheduleInputTable = ref('')
  const scheduleResultTable = ref('')
  const scheduleInputFilter = ref('')
  const scheduleTableOptions = ref([])

  // Batch predict dialog
  const showBatchPredictDialog = ref(false)
  const batchPredictModel = ref(null)
  const batchInputTable = ref('')
  const batchResultTable = ref('')
  const batchPredictLoading = ref(false)
  const batchPredictResult = ref(null)
  const resultPreview = ref([])
  const resultPreviewColumns = ref([])
  const batchTableOptions = ref([])

  // Predict results dialog
  const showPredictResultsDialog = ref(false)
  const predictResultsModel = ref(null)
  const predictionResults = ref([])
  const loadingPredictions = ref(false)

  // Prediction dialog
  const showPredictDialog = ref(false)
  const predictModel_ref = ref(null)
  const predictInput = ref('[{"col1": 0}]')
  const predictSaveTable = ref('')
  const predictLoading = ref(false)
  const predictResult = ref(null)

  function showTrainingResult(model) {
    if (model.status === MODEL_STATUS.TRAINED) {
      const metrics = parsedMetrics(model.metrics)
      const primaryKeys = ['accuracy', 'f1', 'r2']
      const primary = primaryKeys.find(k => metrics[k] != null)
      const parts = []
      if (primary) {
        const val = metrics[primary]
        const pct = ['accuracy', 'f1', 'precision', 'recall', 'r2'].includes(primary)
        parts.push(`${formatMetricName(primary)} = ${pct ? (val * 100).toFixed(1) + '%' : val.toFixed(4)}`)
      }
      if (metrics.overfitting_gap != null) {
        parts.push(`过拟合差距 ${(metrics.overfitting_gap * 100).toFixed(1)}%`)
      }
      if (metrics.train_accuracy != null && metrics.test_accuracy != null) {
        parts.push(`训练/测试 ${(metrics.train_accuracy * 100).toFixed(1)}% / ${(metrics.test_accuracy * 100).toFixed(1)}%`)
      }
      if (metrics.train_r2 != null && metrics.test_r2 != null) {
        parts.push(`训练/测试 R² ${metrics.train_r2.toFixed(3)} / ${metrics.test_r2.toFixed(3)}`)
      }
      ElMessage.success(parts.length ? `训练完成！${parts.join('，')}` : '训练完成！')
    } else if (model.status === MODEL_STATUS.FAILED) {
      ElMessage.error('训练失败，请查看执行历史了解详情')
    } else {
      ElMessage.info('训练状态: ' + (STATUS_LABELS[model.status] || model.status))
    }
  }

  async function handleTrain(id, detailModel) {
    if (trainingId.value) return
    trainingId.value = id
    try {
      const model = await trainMiningModel(id)
      mining.updateModelInList(model)

      if (model.status === MODEL_STATUS.TRAINING) {
        ElMessage.info('训练已启动，正在监听状态...')
        mining.watchModelStatus(id)
        await new Promise((resolve) => {
          const unwatch = watch(
            () => mining.models.find(m => m.id === id),
            (m) => {
              if (m && [MODEL_STATUS.TRAINED, MODEL_STATUS.FAILED, MODEL_STATUS.PUBLISHED].includes(m.status)) {
                showTrainingResult(m)
                unwatch()
                if (activeCleanup.unwatch === unwatch) activeCleanup.unwatch = null
                resolve()
              }
            },
            { deep: true }
          )
          activeCleanup.unwatch = unwatch
          activeCleanup.timeoutId = setTimeout(async () => {
            activeCleanup.unwatch = null; activeCleanup.timeoutId = null
            unwatch(); await mining.refreshModel(id); resolve()
          }, TRAINING_SAFETY_TIMEOUT_MS)
        })
      } else {
        showTrainingResult(model)
      }
      if (detailModel?.value?.id === id) {
        detailModel.value = mining.models.find(m => m.id === id) || model
      }
    } catch (e) {
      ElMessage.error('训练失败: ' + (e.message || '未知错误'))
    } finally {
      trainingId.value = null
    }
  }

  async function handlePublish(id, models, detailModel) {
    const model = models.value.find(m => m.id === id)
    if (!model) return
    publishModel_ref.value = model
    publishConfig.value = {
      predictInputTable: model.predictInputTable || model.sourceTable || '',
      predictInputFilter: model.predictInputFilter || '',
      predictResultTable: model.predictResultTable || ''
    }
    showPublishDialog.value = true
    if (model.dataSourceId) {
      fetchDataSourceTables(model.dataSourceId).then(tables => {
        publishTableOptions.value = tables || []
      }).catch(() => {})
    }
  }

  function buildPublishConfig(force = false) {
    return {
      predictInputTable: publishConfig.value.predictInputTable || null,
      predictInputFilter: publishConfig.value.predictInputFilter || null,
      predictResultTable: publishConfig.value.predictResultTable || null,
      ...(force ? { force: true } : {})
    }
  }

  async function confirmPublish(detailModel) {
    if (!publishModel_ref.value) return
    publishLoading.value = true
    try {
      const model = await publishMiningModel(publishModel_ref.value.id, buildPublishConfig())
      mining.updateModelInList(model)
      if (detailModel?.value?.id === publishModel_ref.value.id) detailModel.value = model
      showPublishDialog.value = false
      ElMessage.success('机器学习算子版本已提交审批；审批通过后进入统一算子库，调度请在穿透式监控模型中配置')
    } catch (e) {
      if (e.message && e.message.includes('过拟合')) {
        try {
          await ElMessageBox.confirm(e.message, '过拟合警告', {
            confirmButtonText: '强制发布', cancelButtonText: '取消',
            type: 'warning', dangerouslyUseHTMLString: false
          })
          publishLoading.value = true
          const model = await publishMiningModel(publishModel_ref.value.id, buildPublishConfig(true))
          mining.updateModelInList(model)
          if (detailModel?.value?.id === publishModel_ref.value.id) detailModel.value = model
          showPublishDialog.value = false
          ElMessage.success('机器学习算子版本已提交审批；审批通过后进入统一算子库')
        } catch { /* user cancelled */ }
      } else {
        ElMessage.error('发布失败: ' + (e.message || '未知错误'))
      }
    } finally {
      publishLoading.value = false
    }
  }

  async function handleOffline(id, detailModel) {
    const model = mining.models.find(m => m.id === id)
    try {
      await ElMessageBox.confirm(
        `确定将「${model?.name || id}」下线？${model?.scheduleEnabled ? '定时调度将同时停止。' : ''}`,
        '下线机器学习算子',
        { confirmButtonText: '确定下线', cancelButtonText: '取消', type: 'warning' }
      )
    } catch { return }
    try {
      const result = await offlineMiningModel(id)
      mining.updateModelInList(result)
      ElMessage.success('机器学习算子已下线')
      if (detailModel?.value?.id === id) detailModel.value = result
    } catch (e) {
      ElMessage.error(e.message || '操作失败')
    }
  }

  async function handleDelete(id, name, showDetail, detailModel) {
    const model = mining.models.find(m => m.id === id)
    if (!model) return

    // 幽灵模型判断已抽到 utils/modelGhost.js（消除重复 + 集中硬编码）

    const isPublished = model.status === MODEL_STATUS.PUBLISHED
    const isTraining = model.status === MODEL_STATUS.TRAINING

    let confirmMessage = `确定删除机器学习算子「${name}」吗？`
    let useForceDelete = false

    if (isGhostModel(model)) {
      confirmMessage += `\n\n该算子的训练制品缺失或状态异常，将使用强制删除。`
      useForceDelete = true
    } else if (isPublished) {
      confirmMessage += `\n\n这是已发布算子，需要使用强制删除。`
      useForceDelete = true
    } else if (isTraining) {
      confirmMessage += `\n\n这是训练中的算子，需要使用强制删除。`
      useForceDelete = true
    } else {
      confirmMessage += `此操作不可撤销。`
    }

    try {
      await ElMessageBox.confirm(confirmMessage, '删除机器学习算子', {
        confirmButtonText: useForceDelete ? '强制删除' : '删除',
        cancelButtonText: '取消',
        type: useForceDelete ? 'error' : 'warning'
      })

      if (useForceDelete) {
        await forceDeleteMiningModel(id)
        ElMessage.success('已强制删除')
      } else {
        await deleteMiningModel(id)
        ElMessage.success('已删除')
      }

      mining.removeModel(id)
      if (detailModel?.value?.id === id) showDetail.value = false
    } catch (error) {
      if (error !== 'cancel') {
        ElMessage.error('删除失败: ' + (error.message || '未知错误'))
      }
    }
  }

  function openSchedule(model) {
    scheduleModel.value = model
    scheduleCron.value = model.scheduleCron || '0 6 * * *'
    scheduleEnabled.value = !!model.scheduleEnabled
    scheduleMode.value = model.scheduleMode || 'train'
    scheduleInputTable.value = model.predictInputTable || ''
    scheduleResultTable.value = model.predictResultTable || ''
    scheduleInputFilter.value = model.predictInputFilter || ''
    showScheduleDialog.value = true
    if (model.dataSourceId) {
      fetchDataSourceTables(model.dataSourceId).then(tables => {
        scheduleTableOptions.value = tables || []
      }).catch(() => {})
    }
  }

  async function saveSchedule(loadModels) {
    if (!scheduleModel.value) return
    if (scheduleMode.value === 'predict' && !scheduleInputTable.value) {
      ElMessage.warning('预测模式需要选择输入表')
      return
    }
    try {
      await updateModelSchedule(
        scheduleModel.value.id, scheduleCron.value,
        scheduleEnabled.value, scheduleMode.value
      )
      if (scheduleMode.value === 'predict') {
        try {
          await updateMiningModel(scheduleModel.value.id, {
            predictInputTable: scheduleInputTable.value || null,
            predictResultTable: scheduleResultTable.value || null,
            predictInputFilter: scheduleInputFilter.value || null
          })
        } catch (e) {
          ElMessage.warning('调度已保存，但预测配置更新失败: ' + (e.message || ''))
        }
      }
      const refreshed = await fetchMiningModel(scheduleModel.value.id)
      if (refreshed) mining.updateModelInList(refreshed)
      ElMessage.success(scheduleEnabled.value ? '调度已启用' : '调度已更新')
      showScheduleDialog.value = false
    } catch (e) {
      ElMessage.error('保存调度失败: ' + (e.message || ''))
    }
  }

  function openBatchPredict(model) {
    batchPredictModel.value = model
    batchInputTable.value = model.predictInputTable || ''
    batchResultTable.value = model.predictResultTable || ''
    batchPredictResult.value = null
    resultPreview.value = []
    resultPreviewColumns.value = []
    showBatchPredictDialog.value = true
    if (model.dataSourceId) {
      fetchDataSourceTables(model.dataSourceId).then(tables => {
        batchTableOptions.value = tables || []
      }).catch(() => {})
    }
  }

  async function handleBatchPredict() {
    if (!batchInputTable.value) {
      ElMessage.warning('请选择输入表')
      return
    }
    batchPredictLoading.value = true
    batchPredictResult.value = null
    try {
      const result = await batchPredictMiningModel(batchPredictModel.value.id, {
        inputTable: batchInputTable.value,
        resultTable: batchResultTable.value || undefined
      })
      batchPredictResult.value = result
      ElMessage.success(`批量预测完成，${result.saved_rows} 条结果已写入 ${result.saved_to}`)
    } catch (e) {
      ElMessage.error('批量预测失败: ' + (e.message || '未知错误'))
    } finally {
      batchPredictLoading.value = false
    }
  }

  async function previewResult(tableName) {
    if (!tableName || !batchPredictModel.value) return
    resultPreview.value = []
    resultPreviewColumns.value = []
    try {
      const res = await previewResultTable(batchPredictModel.value.id, tableName, 10)
      resultPreview.value = res.rows || []
      resultPreviewColumns.value = res.columns || []
    } catch (e) {
      ElMessage.error('预览结果数据失败: ' + (e.message || '未知错误'))
    }
  }

  async function openPredictResults(model) {
    predictResultsModel.value = model
    predictionResults.value = []
    showPredictResultsDialog.value = true
    loadingPredictions.value = true
    try {
      predictionResults.value = await fetchModelPredictions(model.id, 200) || []
    } catch {
      predictionResults.value = []
    } finally {
      loadingPredictions.value = false
    }
  }

  function openPredict(model) {
    predictModel_ref.value = model
    try {
      const cols = JSON.parse(model.featureColumns || '[]')
      const sample = {}
      cols.forEach(c => { sample[c] = 0 })
      predictInput.value = JSON.stringify([sample], null, 2)
    } catch { predictInput.value = '[{"col1": 0}]' }
    predictSaveTable.value = model.sourceTable ? model.sourceTable + '_prediction' : ''
    predictResult.value = null
    showPredictDialog.value = true
  }

  async function handlePredict() {
    let inputRows
    try {
      inputRows = JSON.parse(predictInput.value)
      if (!Array.isArray(inputRows) || inputRows.length === 0) throw new Error()
    } catch {
      ElMessage.error('输入数据格式错误，需要JSON数组')
      return
    }
    predictLoading.value = true
    predictResult.value = null
    try {
      const result = await predictMiningModel(
        predictModel_ref.value.id, inputRows,
        predictSaveTable.value || null
      )
      predictResult.value = result
      if (result.saved_to) {
        ElMessage.success(`预测完成，${result.saved_rows} 条结果已写入 ${result.saved_to}`)
      } else {
        ElMessage.success(`预测完成，共 ${result.predictions?.length || 0} 条结果`)
      }
    } catch (e) {
      ElMessage.error('预测失败: ' + (e.message || '未知错误'))
    } finally {
      predictLoading.value = false
    }
  }

  async function handleValidate(id) {
    try {
      const result = await validateMiningModel(id)
      const checks = result.checks || result
      const lines = []
      if (typeof checks === 'object') {
        for (const [k, v] of Object.entries(checks)) {
          const icon = v === true || v === 'ok' || v === 'pass' ? '✓' : '✗'
          lines.push(`${icon} ${k}: ${v}`)
        }
      }
      if (lines.length) {
        ElMessageBox.alert(lines.join('\n'), '训练前校验结果', { confirmButtonText: '确定' })
      } else {
        ElMessage.success('校验通过')
      }
    } catch (e) {
      ElMessage.error('校验失败: ' + (e.message || '未知错误'))
    }
  }

  function cleanup() {
    if (activeCleanup.unwatch) activeCleanup.unwatch()
    if (activeCleanup.timeoutId) clearTimeout(activeCleanup.timeoutId)
  }

  return {
    trainingId, activeCleanup,
    showPublishDialog, publishModel_ref, publishLoading, publishConfig, publishTableOptions,
    showScheduleDialog, scheduleModel, scheduleCron, scheduleEnabled, scheduleMode,
    scheduleInputTable, scheduleResultTable, scheduleInputFilter, scheduleTableOptions,
    showBatchPredictDialog, batchPredictModel, batchInputTable, batchResultTable,
    batchPredictLoading, batchPredictResult, resultPreview, resultPreviewColumns, batchTableOptions,
    showPredictResultsDialog, predictResultsModel, predictionResults, loadingPredictions,
    showPredictDialog, predictModel_ref, predictInput, predictSaveTable, predictLoading, predictResult,
    handleTrain, showTrainingResult,
    handlePublish, buildPublishConfig, confirmPublish,
    handleOffline, handleDelete,
    openSchedule, saveSchedule,
    openBatchPredict, handleBatchPredict, previewResult,
    openPredictResults, openPredict, handlePredict,
    handleValidate, cleanup
  }
}
