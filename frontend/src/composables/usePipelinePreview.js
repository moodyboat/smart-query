import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  previewStepPipeline, getStepScript, updateMiningPipeline, validateMiningPipeline
} from '../api'

/**
 * Composable for pipeline preview and script viewing logic.
 * Extracted from PipelineEditor.vue to reduce component size.
 */
export function usePipelinePreview() {
  const previewingNodeId = ref(null)
  const previewResult = ref(null)
  const showPreviewDrawer = ref(false)
  const showScriptDrawer = ref(false)
  const scriptContent = ref('')
  const scriptLoading = ref(false)

  async function previewStep(pipelineId, nodeId, silentSave) {
    if (!pipelineId || previewingNodeId.value) return
    previewingNodeId.value = nodeId
    previewResult.value = null
    try {
      if (silentSave) await silentSave()
      const result = await previewStepPipeline(pipelineId, nodeId)
      previewResult.value = result
      if (result.status === 'success') {
        ElMessage.success('试运行完成')
      } else {
        ElMessage.warning('试运行出错: ' + (result.error || '未知错误'))
      }
      showPreviewDrawer.value = true
    } catch (e) {
      ElMessage.error('试运行失败: ' + (e.message || ''))
      previewResult.value = { nodeId, status: 'error', error: e.message }
      showPreviewDrawer.value = true
    } finally {
      previewingNodeId.value = null
    }
  }

  async function viewScript(pipelineId, nodeId, silentSave) {
    if (!pipelineId || scriptLoading.value) return
    scriptLoading.value = true
    scriptContent.value = ''
    try {
      if (silentSave) await silentSave()
      const result = await getStepScript(pipelineId, nodeId)
      scriptContent.value = result.script || ''
      showScriptDrawer.value = true
    } catch (e) {
      ElMessage.error('获取脚本失败: ' + (e.message || ''))
    } finally {
      scriptLoading.value = false
    }
  }

  function copyScript() {
    if (!scriptContent.value) return
    navigator.clipboard.writeText(scriptContent.value).then(() => {
      ElMessage.success('已复制到剪贴板')
    }).catch(() => {
      ElMessage.error('复制失败')
    })
  }

  function showPreviewPanel() {
    showPreviewDrawer.value = true
  }

  return {
    previewingNodeId,
    previewResult,
    showPreviewDrawer,
    showScriptDrawer,
    scriptContent,
    scriptLoading,
    previewStep,
    viewScript,
    copyScript,
    showPreviewPanel
  }
}
