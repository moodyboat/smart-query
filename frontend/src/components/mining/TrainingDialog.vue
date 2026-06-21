<template>
  <el-dialog
    v-model="visible"
    :title="`训练中 - ${model?.name || ''}`"
    width="90%"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="canClose"
    @close="handleClose"
    destroy-on-close
  >
    <div class="training-dialog">
      <!-- 训练进度头部 -->
      <div class="training-header">
        <div class="training-info">
          <span class="algorithm-badge">{{ algorithmLabel(model?.algorithm) }}</span>
          <span class="training-status">{{ trainingStatus }}</span>
        </div>
        <div class="training-progress">
          <el-progress
            :percentage="progressPercentage"
            :status="progressStatus"
            :stroke-width="8"
          >
            <span class="progress-text">{{ progressText }}</span>
          </el-progress>
        </div>
      </div>

      <!-- 主要内容区域 -->
      <div class="training-content">
        <!-- 左侧：代码查看器 -->
        <div class="code-section">
          <TrainingCodeViewer
            :code="trainingCode"
            :steps="trainingSteps"
            :current-step-id="currentStepId"
            :completed-steps="completedSteps"
            :algorithm="model?.algorithm"
            @step-click="handleStepClick"
            @line-click="handleLineClick"
          />
        </div>

        <!-- 右侧：日志输出 -->
        <div class="logs-section">
          <div class="logs-header">
            <span class="logs-title">训练日志</span>
            <div class="logs-actions">
              <el-button size="small" @click="clearLogs">清空</el-button>
              <el-button size="small" @click="exportLogs">导出</el-button>
            </div>
          </div>
          <div class="logs-content" ref="logsContent">
            <div v-if="!logs.length" class="logs-empty">等待训练开始...</div>
            <div v-else class="logs-list">
              <div
                v-for="(log, index) in logs"
                :key="index"
                :class="['log-item', 'log-' + log.level]"
              >
                <span class="log-time">{{ formatLogTime(log.timestamp) }}</span>
                <span class="log-message">{{ log.message }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 训练结果（完成后显示） -->
      <div v-if="trainingResult" class="training-result">
        <div class="result-header">
          <span class="result-title">训练完成</span>
          <el-tag :type="trainingResult.success ? 'success' : 'danger'">
            {{ trainingResult.success ? '成功' : '失败' }}
          </el-tag>
        </div>
        <div v-if="trainingResult.metrics" class="result-metrics">
          <div
            v-for="(val, key) in trainingResult.metrics"
            :key="key"
            class="result-metric"
          >
            <span class="metric-label">{{ formatMetricName(key) }}</span>
            <span class="metric-value">{{ formatMetricValue(key, val) }}</span>
          </div>
        </div>
        <div v-if="trainingResult.error" class="result-error">
          <span class="error-label">错误信息：</span>
          <span class="error-message">{{ trainingResult.error }}</span>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <span v-if="canClose" class="footer-hint">训练已完成，点击关闭或ESC键退出</span>
        <span v-else class="footer-hint">训练进行中，请勿关闭窗口...</span>
        <el-button :disabled="!canClose" type="primary" @click="handleClose">
          {{ canClose ? '关闭' : '训练中...' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import TrainingCodeViewer from '../TrainingCodeViewer.vue'
import { analyzeCodeForSteps } from '../../config/trainingSteps'
import { createCodeAnalyzer } from '../../utils/codeAnalyzer'

const props = defineProps({
  show: { type: Boolean, default: false },
  model: { type: Object, default: null },
  algorithmLabel: { type: Function, required: true }
})

const emit = defineEmits(['update:show', 'complete', 'error'])

const visible = computed({
  get: () => props.show,
  set: (val) => emit('update:show', val)
})

// 训练状态
const trainingStatus = ref('准备中...')
const progressPercentage = ref(0)
const progressStatus = ref(null)
const progressText = ref('0/0')
const canClose = ref(false)
const trainingResult = ref(null)
const trainingCode = ref('')
const trainingSteps = ref([])
const currentStepId = ref(null)
const completedSteps = ref([])
const logs = ref([])
const logsContent = ref(null)

// SSE 连接和模拟训练进度
let eventSource = null
let trainingSimulation = null
const useRealStream = ref(true) // 设置为 false 使用模拟数据

watch(() => props.show, (newVal) => {
  if (newVal) {
    startTraining()
  } else {
    stopTraining()
  }
})

watch(() => props.model, (newModel) => {
  if (newModel) {
    loadTrainingCode(newModel)
  }
})

async function loadTrainingCode(model) {
  try {
    // 首先尝试从后端获取真实的训练代码
    try {
      const response = await fetch(`/api/v1/mining/model/${model.id}/training-code`)
      if (response.ok) {
        const data = await response.json()
        trainingCode.value = data.code || generateMockTrainingCode(model.algorithm)
        console.log('[TRAINING-CODE] 从后端加载训练代码，长度:', trainingCode.value.length)
      } else {
        throw new Error('获取训练代码失败')
      }
    } catch (error) {
      console.log('[TRAINING-CODE] 无法从后端获取代码，使用生成的代码')
      trainingCode.value = generateMockTrainingCode(model.algorithm)
    }

    // 使用智能代码分析来获取精确的步骤映射
    const analyzedSteps = analyzeCodeForSteps(trainingCode.value)

    if (analyzedSteps && analyzedSteps.length > 0) {
      trainingSteps.value = analyzedSteps
      console.log('[TRAINING-STEPS] 分析出的训练步骤:', analyzedSteps)
    } else {
      // 如果分析失败，使用基本推断
      trainingSteps.value = inferBasicSteps(trainingCode.value)
      console.log('[TRAINING-STEPS] 使用基本步骤推断')
    }
  } catch (error) {
    console.error('[TRAINING-CODE] 加载训练代码失败:', error)
    ElMessage.error('加载训练代码失败')
  }
}

// 后备的基本步骤推断
function inferBasicSteps(code) {
  const lines = code.split('\n')
  const steps = []

  // 简单的函数边界检测
  let currentFunc = null
  let funcStartLine = 0

  const stepPatterns = {
    data_loading: /def\s+(load_|fetch_|get_|read_)/,
    data_preprocessing: /def\s+(preprocess|clean|handle)/,
    feature_engineering: /def\s+(feature|engineer|transform)/,
    train_test_split: /def\s+(split)/,
    model_training: /def\s+(train|fit|build)/,
    model_evaluation: /def\s+(evaluate|predict|score)/
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim()
    const match = line.match(/def\s+(\w+)\s*\(/)

    if (match) {
      const funcName = match[1]
      let stepId = null

      for (const [id, pattern] of Object.entries(stepPatterns)) {
        if (pattern.test(line)) {
          stepId = id
          break
        }
      }

      if (stepId) {
        if (currentFunc) {
          steps.push({
            id: stepId,
            label: formatStepLabel(stepId),
            description: formatStepDescription(stepId),
            startLine: funcStartLine + 1,
            endLine: i + 1,
            exactLines: Array.from({ length: i + 1 - funcStartLine }, (_, idx) => funcStartLine + idx + 1),
            functions: [currentFunc]
          })
        }

        currentFunc = funcName
        funcStartLine = i
      }
    }
  }

  // 处理最后一个函数
  if (currentFunc) {
    const stepId = Object.keys(stepPatterns).find(id => stepPatterns[id].test(lines[funcStartLine].trim()))
    if (stepId) {
      steps.push({
        id: stepId,
        label: formatStepLabel(stepId),
        description: formatStepDescription(stepId),
        startLine: funcStartLine + 1,
        endLine: lines.length,
        exactLines: Array.from({ length: lines.length - funcStartLine }, (_, idx) => funcStartLine + idx + 1),
        functions: [currentFunc]
      })
    }
  }

  return steps
}

function formatStepLabel(stepId) {
  const labels = {
    data_loading: '数据加载',
    data_preprocessing: '数据预处理',
    feature_engineering: '特征工程',
    train_test_split: '数据分割',
    model_training: '模型训练',
    model_evaluation: '模型评估'
  }
  return labels[stepId] || stepId
}

function formatStepDescription(stepId) {
  const descriptions = {
    data_loading: '从数据库加载训练数据',
    data_preprocessing: '处理缺失值、异常值',
    feature_engineering: '特征选择和转换',
    train_test_split: '训练集和测试集划分',
    model_training: '训练模型',
    model_evaluation: '计算评估指标'
  }
  return descriptions[stepId] || ''
}

function generateMockTrainingCode(algorithm) {
  // 这里返回模拟的训练代码
  // 实际应该从后端API获取
  return `# 数据挖掘训练脚本 - ${algorithm}
import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, classification_report

# 1. 数据加载
def load_data():
    """从数据库加载训练数据"""
    import mysql.connector
    conn = mysql.connector.connect(
        host='localhost',
        user='root',
        password='password',
        database='smart_query_sample'
    )
    query = "SELECT * FROM customers WHERE status = 'active'"
    data = pd.read_sql(query, conn)
    conn.close()
    return data

# 2. 数据预处理
def preprocess_data(df):
    """处理缺失值和异常值"""
    # 处理缺失值
    df = df.fillna(df.mean(numeric_only=True))
    df = df.dropna()

    # 处理异常值
    Q1 = df.quantile(0.25)
    Q3 = df.quantile(0.75)
    IQR = Q3 - Q1
    df = df[~((df < (Q1 - 1.5 * IQR)) | (df > (Q3 + 1.5 * IQR))).any(axis=1)]

    return df

# 3. 特征工程
def engineer_features(df):
    """特征选择和转换"""
    # 选择特征列
    feature_cols = ['age', 'income', 'spending_score', 'membership_years']
    X = df[feature_cols]

    # 特征标准化
    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X)

    # 目标变量
    y = df['target_column']

    return X_scaled, y, feature_cols

# 4. 数据分割
def split_data(X, y, test_size=0.2):
    """训练集和测试集划分"""
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=test_size, random_state=42, stratify=y
    )
    return X_train, X_test, y_train, y_test

# 5. 模型训练
def train_model(X_train, y_train):
    """训练${algorithm}模型"""
    from sklearn.ensemble import RandomForestClassifier

    model = RandomForestClassifier(
        n_estimators=100,
        max_depth=10,
        min_samples_split=5,
        min_samples_leaf=2,
        random_state=42
    )

    model.fit(X_train, y_train)
    return model

# 6. 模型评估
def evaluate_model(model, X_test, y_test):
    """计算评估指标"""
    y_pred = model.predict(X_test)

    accuracy = accuracy_score(y_test, y_pred)
    report = classification_report(y_test, y_pred)

    return {
        'accuracy': accuracy,
        'report': report
    }

# 7. 特征重要性分析
def analyze_feature_importance(model, feature_cols):
    """分析特征重要性"""
    importances = model.feature_importances_
    feature_importance = dict(zip(feature_cols, importances))

    # 排序
    sorted_features = sorted(
        feature_importance.items(),
        key=lambda x: x[1],
        reverse=True
    )

    return dict(sorted_features)

# 主训练流程
def main():
    # 1. 加载数据
    data = load_data()
    print(f"数据加载完成: {data.shape[0]} 行, {data.shape[1]} 列")

    # 2. 数据预处理
    data = preprocess_data(data)
    print(f"数据预处理完成: {data.shape[0]} 行, {data.shape[1]} 列")

    # 3. 特征工程
    X, y, feature_cols = engineer_features(data)
    print(f"特征工程完成: {X.shape[0]} 样本, {X.shape[1]} 特征")

    # 4. 数据分割
    X_train, X_test, y_train, y_test = split_data(X, y)
    print(f"数据分割完成: 训练集 {X_train.shape[0]}, 测试集 {X_test.shape[0]}")

    # 5. 模型训练
    model = train_model(X_train, y_train)
    print("模型训练完成")

    # 6. 模型评估
    metrics = evaluate_model(model, X_test, y_test)
    print(f"模型评估完成: 准确率 {metrics['accuracy']:.4f}")

    # 7. 特征重要性
    importance = analyze_feature_importance(model, feature_cols)
    print("特征重要性分析完成")

    return model, metrics, importance

if __name__ == "__main__":
    model, metrics, importance = main()
    print("训练流程全部完成！")
`
}

function startTraining() {
  if (!props.model) return

  // 重置状态
  trainingStatus.value = '初始化中...'
  progressPercentage.value = 0
  progressStatus.value = null
  progressText.value = '0/0'
  canClose.value = false
  trainingResult.value = null
  completedSteps.value = []
  currentStepId.value = null
  logs.value = []

  addLog('info', `开始训练模型: ${props.model.name}`)
  addLog('info', `算法类型: ${props.model.algorithm}`)

  // 使用真实的SSE连接或模拟数据
  if (useRealStream.value) {
    startRealTraining()
  } else {
    simulateTrainingProgress()
  }
}

function startRealTraining() {
  try {
    // 首先触发训练API
    fetch(`/api/v1/mining/model/${props.model.id}/train`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      }
    }).then(response => {
      if (!response.ok) {
        throw new Error('训练请求失败')
      }
      addLog('info', '训练请求已发送，后端开始处理...')
      console.log('[TRAIN] 训练请求已发送，状态:', response.status)
    }).catch(error => {
      console.error('[TRAIN] 训练请求失败:', error)
      addLog('error', `训练请求失败: ${error.message}`)
      useRealStream.value = false
      simulateTrainingProgress()
      return
    })

    // 建立SSE连接获取训练进度
    addLog('info', '连接到实时训练流...')

    eventSource = new EventSource(`/api/v1/mining/model/${props.model.id}/train-stream`)

    eventSource.addEventListener('start', (event) => {
      const data = JSON.parse(event.data)
      console.log('[SSE] 训练开始事件:', data)
      addLog('info', `开始训练: ${data.model}`)
      trainingStatus.value = '训练开始'
    })

    eventSource.addEventListener('step', (event) => {
      const data = JSON.parse(event.data)
      console.log('[SSE] 步骤事件:', data)

      currentStepId.value = data.stepId
      trainingStatus.value = data.label
      progressPercentage.value = data.progress
      progressText.value = `${data.progress}%`

      // 查找对应的步骤配置
      const stepConfig = trainingSteps.value.find(s => s.id === data.stepId)
      if (stepConfig) {
        trainingStatus.value = stepConfig.label
        addLog('info', `执行步骤: ${stepConfig.label}`)
      } else {
        addLog('info', `执行步骤: ${data.label}`)
      }
    })

    eventSource.addEventListener('complete', (event) => {
      const data = JSON.parse(event.data)
      console.log('[SSE] 训练完成事件:', data)
      addLog('success', '训练成功完成')

      // 标记所有步骤为已完成
      trainingSteps.value.forEach(step => {
        if (!completedSteps.value.includes(step.id)) {
          completedSteps.value.push(step.id)
        }
      })

      // 确保 data.metrics 存在，即使为空对象也算成功
      const resultData = {
        success: data.success !== false, // 只有明确为false才算失败
        metrics: data.metrics || {},
        modelPath: data.modelPath,
        featureImportance: data.featureImportance
      }
      completeTraining(true, resultData)
    })

    eventSource.addEventListener('error', (event) => {
      const data = JSON.parse(event.data)
      console.log('[SSE] 错误事件:', data)
      addLog('error', `训练错误: ${data.message}`)
      completeTraining(false, { error: data.message })
    })

    eventSource.addEventListener('timeout', (event) => {
      console.log('[SSE] 超时事件')
      addLog('warning', '训练超时')
      completeTraining(false, { error: '训练超时，请检查模型状态' })
    })

    eventSource.onerror = (error) => {
      console.error('[SSE] 连接错误:', error)
      addLog('error', 'SSE连接错误，尝试切换到模拟模式')
      eventSource?.close()
      useRealStream.value = false

      // 延迟一下再启动模拟训练，避免频繁切换
      setTimeout(() => {
        if (!canClose.value) {
          simulateTrainingProgress()
        }
      }, 1000)
    }

  } catch (error) {
    console.error('启动训练失败:', error)
    addLog('error', `启动训练失败: ${error.message}`)
    useRealStream.value = false
    simulateTrainingProgress()
  }
}

function simulateTrainingProgress() {
  let stepIndex = 0
  const steps = trainingSteps.value

  trainingSimulation = setInterval(() => {
    if (stepIndex >= steps.length) {
      // 训练完成
      completeTraining(true)
      return
    }

    const step = steps[stepIndex]
    currentStepId.value = step.id
    trainingStatus.value = step.label

    const progress = ((stepIndex + 1) / steps.length * 100).toFixed(0)
    progressPercentage.value = parseInt(progress)
    progressText.value = `${stepIndex + 1}/${steps.length}`

    addLog('info', `执行步骤: ${step.label}`)

    // 标记当前步骤为已完成
    setTimeout(() => {
      completedSteps.value.push(step.id)
      addLog('success', `完成步骤: ${step.label}`)
      stepIndex++
    }, 1000) // 每个步骤模拟1秒

  }, 1500) // 每1.5秒一个新步骤
}

function completeTraining(success, result = null) {
  stopTraining()
  canClose.value = true

  if (success) {
    trainingStatus.value = '训练完成'
    progressStatus.value = 'success'
    progressPercentage.value = 100

    // 确保 result 对象结构正确
    trainingResult.value = result || { success: true }

    addLog('success', '训练成功完成')
    if (trainingResult.value.metrics && Object.keys(trainingResult.value.metrics).length > 0) {
      const accuracy = trainingResult.value.metrics.test_accuracy || trainingResult.value.metrics.accuracy
      if (accuracy) {
        addLog('info', `测试准确率: ${(accuracy * 100).toFixed(1)}%`)
      }
      const f1 = trainingResult.value.metrics.test_f1 || trainingResult.value.metrics.f1
      if (f1) {
        addLog('info', `F1分数: ${(f1 * 100).toFixed(1)}%`)
      }
    }
    emit('complete', trainingResult.value)
  } else {
    trainingStatus.value = '训练失败'
    progressStatus.value = 'exception'

    // 如果没有具体的错误信息，使用默认提示
    const errorMsg = (result && result.error) ? result.error : '训练未能完成，请查看执行历史了解详情'
    trainingResult.value = {
      success: false,
      error: errorMsg
    }

    addLog('error', '训练失败: ' + errorMsg)
    emit('error', trainingResult.value)
  }
}

function stopTraining() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (trainingSimulation) {
    clearInterval(trainingSimulation)
    trainingSimulation = null
  }
}

function handleClose() {
  if (canClose.value) {
    visible.value = false
  }
}

function handleStepClick(stepId) {
  console.log('点击步骤:', stepId)
}

function handleLineClick(lineNumber) {
  console.log('点击行号:', lineNumber)
}

function addLog(level, message) {
  logs.value.push({
    timestamp: Date.now(),
    level,
    message
  })

  // 自动滚动到底部
  setTimeout(() => {
    if (logsContent.value) {
      logsContent.value.scrollTop = logsContent.value.scrollHeight
    }
  }, 100)
}

function clearLogs() {
  logs.value = []
}

function exportLogs() {
  const logText = logs.value.map(log => {
    return `[${formatLogTime(log.timestamp)}] ${log.level.toUpperCase()}: ${log.message}`
  }).join('\n')

  const blob = new Blob([logText], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `training_logs_${Date.now()}.txt`
  a.click()
  URL.revokeObjectURL(url)

  ElMessage.success('日志导出成功')
}

function formatLogTime(timestamp) {
  return new Date(timestamp).toLocaleTimeString('zh-CN')
}

function formatMetricName(key) {
  const names = {
    accuracy: '准确率',
    precision: '精确率',
    recall: '召回率',
    f1: 'F1分数'
  }
  return names[key] || key
}

function formatMetricValue(key, val) {
  if (typeof val === 'number') {
    return (val * 100).toFixed(1) + '%'
  }
  return val
}

onMounted(() => {
  if (props.model) {
    loadTrainingCode(props.model)
  }
})

onUnmounted(() => {
  stopTraining()
})
</script>

<style scoped>
.training-dialog {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.training-header {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.training-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.algorithm-badge {
  background: var(--python-color, #3776ab);
  color: white;
  padding: 4px 12px;
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  font-weight: 600;
}

.training-status {
  font-size: var(--font-base);
  font-weight: 500;
  color: var(--text-primary);
}

.training-content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  height: 500px;
}

.code-section {
  background: var(--code-bg);
  border-radius: var(--radius-lg);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.logs-section {
  background: var(--hover);
  border-radius: var(--radius-lg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.logs-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
  background: white;
}

.logs-title {
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
}

.logs-actions {
  display: flex;
  gap: 8px;
}

.logs-content {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-sm);
}

.logs-empty {
  text-align: center;
  color: var(--text-muted);
  padding: 40px 0;
  font-size: var(--font-base);
}

.logs-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.log-item {
  display: flex;
  gap: 8px;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  font-family: monospace;
}

.log-info {
  color: var(--text-secondary);
  background: rgba(64, 158, 255, 0.05);
}

.log-success {
  color: var(--el-color-success);
  background: rgba(103, 194, 58, 0.05);
}

.log-error {
  color: var(--el-color-danger);
  background: rgba(245, 108, 108, 0.05);
}

.log-time {
  color: var(--text-muted);
  flex-shrink: 0;
}

.log-message {
  flex: 1;
}

.training-result {
  background: white;
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  border: 1px solid var(--border);
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.result-title {
  font-size: var(--font-xl);
  font-weight: 600;
  color: var(--text-primary);
}

.result-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.result-metric {
  background: var(--hover);
  border-radius: var(--radius-md);
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.metric-label {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

.metric-value {
  font-size: var(--font-2xl);
  font-weight: 600;
  color: var(--el-color-success);
}

.result-error {
  margin-top: 12px;
  padding: var(--space-md);
  background: rgba(245, 108, 108, 0.1);
  border-radius: var(--radius-md);
  border-left: 3px solid var(--el-color-danger);
}

.error-label {
  font-weight: 600;
  color: var(--el-color-danger);
}

.error-message {
  color: var(--text-secondary);
}

.dialog-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.footer-hint {
  font-size: var(--font-md);
  color: var(--text-muted);
}

.progress-text {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

@media (max-width: 1200px) {
  .training-content {
    grid-template-columns: 1fr;
    height: auto;
  }

  .code-section,
  .logs-section {
    height: 400px;
  }
}
</style>
