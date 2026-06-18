/**
 * 训练步骤映射配置
 * 基于函数名的精确映射，支持代码片段级别的识别
 */

import { createCodeAnalyzer } from '../utils/codeAnalyzer.js'

/**
 * 训练步骤函数映射配置
 * 定义每个步骤对应的函数名模式
 */
export const TRAINING_FUNCTION_PATTERNS = {
  // 数据加载相关函数
  data_loading: {
    patterns: ['load_data', 'fetch_data', 'get_data', 'read_data', 'load_dataset', 'import_data'],
    description: '从数据库加载训练数据',
    label: '数据加载',
    highlightClass: 'step-loading'
  },

  // 数据预处理相关函数
  data_preprocessing: {
    patterns: ['preprocess_data', 'clean_data', 'handle_missing', 'process_data', 'preprocessing', 'clean_data'],
    description: '处理缺失值、异常值',
    label: '数据预处理',
    highlightClass: 'step-preprocessing'
  },

  // 特征工程相关函数
  feature_engineering: {
    patterns: ['feature_engineering', 'engineer_features', 'transform_features', 'create_features', 'select_features', 'feature_selection'],
    description: '特征选择和转换',
    label: '特征工程',
    highlightClass: 'step-feature'
  },

  // 数据分割相关函数
  train_test_split: {
    patterns: ['split_data', 'train_test_split', 'create_splits', 'partition_data'],
    description: '训练集和测试集划分',
    label: '数据分割',
    highlightClass: 'step-split'
  },

  // 模型训练相关函数
  model_training: {
    patterns: ['train_model', 'fit_model', 'build_model', 'create_model', 'train', 'fit'],
    description: '训练模型',
    label: '模型训练',
    highlightClass: 'step-training'
  },

  // 模型评估相关函数
  model_evaluation: {
    patterns: ['evaluate_model', 'test_model', 'validate_model', 'predict', 'evaluate', 'score_model'],
    description: '计算评估指标',
    label: '模型评估',
    highlightClass: 'step-evaluation'
  },

  // 特征重要性分析相关函数
  feature_importance: {
    patterns: ['analyze_feature_importance', 'get_feature_importance', 'feature_importance', 'plot_importance'],
    description: '分析特征重要性',
    label: '特征重要性',
    highlightClass: 'step-importance'
  },

  // 最优聚类数相关函数
  optimal_clusters: {
    patterns: ['find_optimal_clusters', 'elbow_method', 'silhouette_analysis', 'determine_k'],
    description: '确定最优聚类数',
    label: '最优聚类数',
    highlightClass: 'step-clusters'
  }
}

/**
 * 基于代码内容智能推断训练步骤（精确版本）
 * @param {string} code - Python 训练代码
 * @returns {Array} 推断的训练步骤
 */
export function inferTrainingSteps(code) {
  if (!code) return []

  try {
    const analyzer = createCodeAnalyzer(code)
    const steps = analyzer.inferStepsFromCode()

    if (steps.length > 0) {
      return steps
    }
  } catch (error) {
    console.warn('代码分析失败，使用基本推断:', error)
  }

  // 如果分析失败，使用基本的关键词推断
  return inferBasicSteps(code)
}

/**
 * 基本步骤推断（后备方案）
 */
function inferBasicSteps(code) {
  const lines = code.split('\n')
  const steps = []
  let currentStep = null
  let startLine = 1

  // 识别常见模式的函数
  const patterns = {
    data_loading: /def\s+(load_data|fetch_data|get_data|read_data)/,
    data_preprocessing: /def\s+(preprocess|clean|handle_missing)/,
    feature_engineering: /def\s+(feature|engineer|transform)/,
    train_test_split: /def\s+(split|train_test_split)/,
    model_training: /def\s+(train|fit|build_model)/,
    model_evaluation: /def\s+(evaluate|predict|score)/,
    feature_importance: /def\s+(importance|analyze_importance)/,
    optimal_clusters: /def\s+(elbow|optimal|silhouette)/
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    let matchedStep = null

    for (const [stepId, pattern] of Object.entries(patterns)) {
      if (pattern.test(line)) {
        matchedStep = stepId
        break
      }
    }

    if (matchedStep && matchedStep !== currentStep) {
      if (currentStep) {
        steps[steps.length - 1].endLine = i
      }

      currentStep = matchedStep
      const config = TRAINING_FUNCTION_PATTERNS[currentStep]

      steps.push({
        id: currentStep,
        label: config?.label || currentStep,
        description: config?.description || '',
        startLine: i + 1,
        endLine: i + 1,
        exactLines: [i + 1],
        highlightClass: config?.highlightClass || ''
      })
    }
  }

  if (steps.length > 0) {
    steps[steps.length - 1].endLine = lines.length
  }

  return steps.length > 0 ? steps : []
}

/**
 * 根据算法类型获取训练步骤配置（保持向后兼容）
 * @param {string} algorithm - 算法类型
 * @returns {Array} 训练步骤配置
 */
export function getTrainingSteps(algorithm) {
  // 这个函数现在主要用于向后兼容
  // 实际使用时应该使用 inferTrainingSteps 结合代码内容
  return Object.entries(TRAINING_FUNCTION_PATTERNS).map(([stepId, config]) => ({
    id: stepId,
    label: config.label,
    description: config.description,
    highlightClass: config.highlightClass,
    // 这些是默认值，实际使用时会被代码分析结果覆盖
    startLine: 1,
    endLine: 100,
    exactLines: []
  }))
}

/**
 * 根据步骤ID获取配置
 */
export function getStepConfig(stepId) {
  return TRAINING_FUNCTION_PATTERNS[stepId]
}

/**
 * 分析代码并获取精确的步骤映射
 */
export function analyzeCodeForSteps(code) {
  const analyzer = createCodeAnalyzer(code)
  const inferredSteps = analyzer.inferStepsFromCode()

  // 为每个步骤添加配置信息
  return inferredSteps.map(step => {
    const config = TRAINING_FUNCTION_PATTERNS[step.id]
    return {
      ...step,
      label: config?.label || step.label,
      description: config?.description || step.description,
      highlightClass: config?.highlightClass || ''
    }
  })
}

export default {
  TRAINING_FUNCTION_PATTERNS,
  getTrainingSteps,
  inferTrainingSteps,
  analyzeCodeForSteps,
  getStepConfig
}
