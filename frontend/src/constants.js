export const DEFAULT_TIMEOUT_MS = 180_000
export const LONG_RUNNING_TIMEOUT_MS = 600_000
export const SSE_SAFETY_TIMEOUT_MS = 300_000
export const TRAINING_SAFETY_TIMEOUT_MS = 300_000

export const DEFAULT_MODEL_TYPE = 'classification'
export const DEFAULT_ALGORITHM = 'random_forest'

export const PREVIEW_ROW_LIMIT = 10
export const PREDICTION_RECORD_LIMIT = 200

export const MODEL_STATUS = Object.freeze({
  DRAFT: 'draft',
  TRAINING: 'training',
  TRAINED: 'trained',
  FAILED: 'failed',
  PUBLISHED: 'published',
  OFFLINE: 'offline',
  QUEUED: 'queued',
})

export const PIPELINE_STATUS = Object.freeze({
  DRAFT: 'draft',
  READY: 'ready',
  RUNNING: 'running',
  COMPLETED: 'completed',
  FAILED: 'failed',
})

export const NODE_TYPES = Object.freeze({
  DATA_SOURCE: 'data_source',
  PREPROCESSING: 'preprocessing',
  FILL_MISSING: 'fill_missing',
  FEATURE_ENGINEERING: 'feature_engineering',
  TRAINING: 'training',
  EVALUATION: 'evaluation',
  OUTPUT: 'output',
})

export const MODEL_TYPE = Object.freeze({
  CLASSIFICATION: 'classification',
  REGRESSION: 'regression',
  CLUSTERING: 'clustering',
  ANOMALY_DETECTION: 'anomaly_detection',
})

export const EXECUTION_STATUS = Object.freeze({
  SUCCESS: 'success',
  FAILED: 'failed',
  RUNNING: 'running',
  PENDING: 'pending',
})

// ReAct 对话块执行状态（与 EXECUTION_STATUS 不同：用 error/completed 而非 failed/pending）
export const BLOCK_STATUS = Object.freeze({
  RUNNING: 'running',
  SUCCESS: 'success',
  ERROR: 'error',
  COMPLETED: 'completed',
})

export const NODE_TYPE_LABELS = Object.freeze({
  [NODE_TYPES.DATA_SOURCE]: '数据接入',
  [NODE_TYPES.PREPROCESSING]: '数据预处理',
  [NODE_TYPES.FILL_MISSING]: '填充缺失值',
  [NODE_TYPES.FEATURE_ENGINEERING]: '特征工程',
  [NODE_TYPES.TRAINING]: '模型训练',
  [NODE_TYPES.EVALUATION]: '模型评估',
  [NODE_TYPES.OUTPUT]: '输出写入',
})

export const STATUS_LABELS = Object.freeze({
  [MODEL_STATUS.DRAFT]: '草稿',
  [MODEL_STATUS.TRAINING]: '训练中',
  [MODEL_STATUS.TRAINED]: '已训练',
  [MODEL_STATUS.FAILED]: '训练失败',
  [MODEL_STATUS.PUBLISHED]: '已发布',
  [MODEL_STATUS.OFFLINE]: '已下线',
})

export const PIPELINE_STATUS_LABELS = Object.freeze({
  [PIPELINE_STATUS.DRAFT]: '草稿',
  [PIPELINE_STATUS.READY]: '就绪',
  [PIPELINE_STATUS.RUNNING]: '运行中',
  [PIPELINE_STATUS.COMPLETED]: '已完成',
  [PIPELINE_STATUS.FAILED]: '失败',
})

export const SCHEDULE_INTERVALS = Object.freeze([
  { value: '*/30 * * * *', label: '每 30 分钟' },
  { value: '0 * * * *', label: '每小时整点' },
  { value: '0 6 * * *', label: '每天早上 6:00' },
  { value: '0 8 * * *', label: '每天早上 8:00' },
  { value: '0 0 * * *', label: '每天午夜' },
  { value: '0 8 * * 1', label: '每周一 8:00' },
  { value: '0 0 1 * *', label: '每月 1 号' },
])

export const FILTER_VARIABLES = Object.freeze([
  { value: '${etl_date}', label: 'ETL日期 (当天)', example: "etl_date <= '${etl_date}'" },
  { value: '${today}', label: '今天', example: "create_date = '${today}'" },
  { value: '${yesterday}', label: '昨天', example: "create_date = '${yesterday}'" },
  { value: '${today-N}', label: 'N天前', example: "create_date >= '${today-7}'" },
])
