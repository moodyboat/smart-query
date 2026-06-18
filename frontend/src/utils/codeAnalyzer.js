/**
 * Python 代码分析器
 * 用于精确识别代码片段、函数边界、代码块等
 */

export class PythonCodeAnalyzer {
  constructor(code) {
    this.code = code
    this.lines = code.split('\n')
    this.functionMap = new Map()
    this.classMap = new Map()
    this.codeBlocks = []
    this.imports = []
    this.comments = []

    this.analyze()
  }

  /**
   * 分析代码结构
   */
  analyze() {
    let currentFunction = null
    let currentClass = null
    let functionStartLine = 0
    let classStartLine = 0
    let indentLevel = 0
    let docstringStart = -1

    for (let i = 0; i < this.lines.length; i++) {
      const line = this.lines[i]
      const trimmed = line.trim()
      const currentIndent = line.search(/\S/)

      // 检测导入语句
      if (trimmed.startsWith('import ') || trimmed.startsWith('from ')) {
        this.imports.push({
          line: i + 1,
          content: trimmed,
          type: trimmed.startsWith('from ') ? 'from_import' : 'import'
        })
      }

      // 检测注释
      if (trimmed.startsWith('#') && !trimmed.startsWith('#!')) {
        this.comments.push({
          line: i + 1,
          content: trimmed,
          type: 'line_comment'
        })
      }

      // 检测函数定义
      const functionMatch = trimmed.match(/^def\s+(\w+)\s*\((.*)\)\s*(->\s*\w+)?\s*:/)
      if (functionMatch) {
        // 保存前一个函数
        if (currentFunction) {
          this.functionMap.set(currentFunction, {
            name: currentFunction,
            startLine: functionStartLine,
            endLine: i,
            parameters: functionMatch[2],
            returnType: functionMatch[3]?.replace('->', '').trim(),
            class: currentClass
          })
        }

        currentFunction = functionMatch[1]
        functionStartLine = i + 1
        indentLevel = currentIndent
        docstringStart = -1
      }

      // 检测类定义
      const classMatch = trimmed.match(/^class\s+(\w+)\s*(\([^)]*\))?\s*:/)
      if (classMatch) {
        // 保存前一个类
        if (currentClass) {
          this.classMap.set(currentClass, {
            name: currentClass,
            startLine: classStartLine,
            endLine: i,
            bases: classMatch[2],
            methods: []
          })
        }

        currentClass = classMatch[1]
        classStartLine = i + 1
      }

      // 检测文档字符串
      if (currentFunction && docstringStart === -1) {
        const docstringMatch = trimmed.match(/^"""|^'''/)
        if (docstringMatch) {
          docstringStart = i + 1
        }
      } else if (currentFunction && docstringStart !== -1) {
        const docstringEndMatch = trimmed.match(/^"""|^'''/)
        if (docstringEndMatch) {
          this.functionMap.set(currentFunction, {
            ...this.functionMap.get(currentFunction),
            docstring: {
              startLine: docstringStart,
              endLine: i + 1
            }
          })
          docstringStart = -1
        }
      }

      // 检测函数结束（回到更小的缩进级别）
      if (currentFunction && currentIndent <= indentLevel && i > functionStartLine) {
        // 保存函数
        if (this.functionMap.has(currentFunction)) {
          const func = this.functionMap.get(currentFunction)
          func.endLine = i
          this.functionMap.set(currentFunction, func)
        } else {
          this.functionMap.set(currentFunction, {
            name: currentFunction,
            startLine: functionStartLine,
            endLine: i,
            class: currentClass
          })
        }
        currentFunction = null
      }
    }

    // 保存最后一个函数
    if (currentFunction) {
      this.functionMap.set(currentFunction, {
        name: currentFunction,
        startLine: functionStartLine,
        endLine: this.lines.length,
        class: currentClass
      })
    }

    // 保存最后一个类
    if (currentClass) {
      this.classMap.set(currentClass, {
        name: currentClass,
        startLine: classStartLine,
        endLine: this.lines.length,
        methods: []
      })
    }

    // 构建代码块
    this.buildCodeBlocks()
  }

  /**
   * 构建代码块信息
   */
  buildCodeBlocks() {
    // 导入块
    if (this.imports.length > 0) {
      this.codeBlocks.push({
        type: 'imports',
        name: '导入模块',
        startLine: this.imports[0].line,
        endLine: this.imports[this.imports.length - 1].line + 1,
        lines: this.imports.map(imp => imp.line)
      })
    }

    // 函数块
    this.functionMap.forEach((func, name) => {
      this.codeBlocks.push({
        type: 'function',
        name: name,
        startLine: func.startLine,
        endLine: func.endLine,
        lines: this.getFunctionLines(func)
      })

      // 如果函数属于类，添加到类的方法列表
      if (func.class && this.classMap.has(func.class)) {
        const cls = this.classMap.get(func.class)
        if (!cls.methods) cls.methods = []
        cls.methods.push(name)
      }
    })

    // 类块
    this.classMap.forEach((cls, name) => {
      this.codeBlocks.push({
        type: 'class',
        name: name,
        startLine: cls.startLine,
        endLine: cls.endLine,
        lines: this.getClassLines(cls)
      })
    })
  }

  /**
   * 获取函数的所有相关行
   */
  getFunctionLines(func) {
    const lines = []
    for (let i = func.startLine - 1; i < func.endLine; i++) {
      lines.push(i + 1)
    }
    return lines
  }

  /**
   * 获取类的所有相关行
   */
  getClassLines(cls) {
    const lines = []
    for (let i = cls.startLine - 1; i < cls.endLine; i++) {
      lines.push(i + 1)
    }
    return lines
  }

  /**
   * 根据步骤ID查找相关的代码行
   */
  findLinesForStep(stepId) {
    const stepMapping = {
      'data_loading': ['load_data', 'fetch_data', 'get_data', 'read_data'],
      'data_preprocessing': ['preprocess', 'clean_data', 'handle_missing', 'process_data'],
      'feature_engineering': ['feature', 'engineer', 'transform', 'encode', 'scale'],
      'train_test_split': ['split', 'train_test_split'],
      'model_training': ['train', 'fit', 'build_model', 'create_model'],
      'model_evaluation': ['evaluate', 'predict', 'score', 'validate', 'test'],
      'feature_importance': ['importance', 'feature_importance', 'analyze_importance'],
      'optimal_clusters': ['elbow', 'optimal', 'silhouette', 'inertia']
    }

    const keywords = stepMapping[stepId] || []
    const relevantLines = new Set()

    // 查找匹配的函数
    this.functionMap.forEach((func, name) => {
      if (keywords.some(keyword => name.toLowerCase().includes(keyword))) {
        for (let i = func.startLine; i <= func.endLine; i++) {
          relevantLines.add(i)
        }
      }
    })

    // 查找匹配的代码行（基于关键词）
    keywords.forEach(keyword => {
      this.lines.forEach((line, index) => {
        if (line.toLowerCase().includes(keyword.toLowerCase())) {
          relevantLines.add(index + 1)
        }
      })
    })

    return Array.from(relevantLines).sort((a, b) => a - b)
  }

  /**
   * 获取函数的详细信息
   */
  getFunctionInfo(functionName) {
    return this.functionMap.get(functionName)
  }

  /**
   * 获取所有函数名
   */
  getAllFunctionNames() {
    return Array.from(this.functionMap.keys())
  }

  /**
   * 获取函数列表
   */
  getFunctions() {
    return Array.from(this.functionMap.values())
  }

  /**
   * 根据行号获取所属函数
   */
  getFunctionAtLine(lineNumber) {
    for (const func of this.functionMap.values()) {
      if (lineNumber >= func.startLine && lineNumber <= func.endLine) {
        return func
      }
    }
    return null
  }

  /**
   * 获取代码上下文（指定行周围的代码）
   */
  getContext(lineNumber, contextLines = 3) {
    const start = Math.max(1, lineNumber - contextLines)
    const end = Math.min(this.lines.length, lineNumber + contextLines)

    return {
      startLine: start,
      endLine: end,
      lines: this.lines.slice(start - 1, end).map((line, i) => ({
        lineNumber: start + i,
        content: line,
        isTarget: start + i === lineNumber
      }))
    }
  }

  /**
   * 智能分析代码并推断步骤
   */
  inferStepsFromCode() {
    const steps = []
    const functionGroups = {
      data_loading: [],
      data_preprocessing: [],
      feature_engineering: [],
      train_test_split: [],
      model_training: [],
      model_evaluation: [],
      feature_importance: [],
      optimal_clusters: []
    }

    // 将函数分组到不同步骤
    this.functionMap.forEach((func, name) => {
      const lowerName = name.toLowerCase()

      if (lowerName.includes('load') || lowerName.includes('fetch') || lowerName.includes('read')) {
        functionGroups.data_loading.push(func)
      } else if (lowerName.includes('preprocess') || lowerName.includes('clean') || lowerName.includes('handle')) {
        functionGroups.data_preprocessing.push(func)
      } else if (lowerName.includes('feature') || lowerName.includes('engineer') || lowerName.includes('transform')) {
        functionGroups.feature_engineering.push(func)
      } else if (lowerName.includes('split')) {
        functionGroups.train_test_split.push(func)
      } else if (lowerName.includes('train') || lowerName.includes('fit') || lowerName.includes('build')) {
        functionGroups.model_training.push(func)
      } else if (lowerName.includes('evaluate') || lowerName.includes('predict') || lowerName.includes('score')) {
        functionGroups.model_evaluation.push(func)
      } else if (lowerName.includes('importance')) {
        functionGroups.feature_importance.push(func)
      } else if (lowerName.includes('elbow') || lowerName.includes('optimal') || lowerName.includes('silhouette')) {
        functionGroups.optimal_clusters.push(func)
      }
    })

    // 为每个步骤创建配置
    const stepLabels = {
      data_loading: '数据加载',
      data_preprocessing: '数据预处理',
      feature_engineering: '特征工程',
      train_test_split: '数据分割',
      model_training: '模型训练',
      model_evaluation: '模型评估',
      feature_importance: '特征重要性',
      optimal_clusters: '最优聚类数'
    }

    Object.entries(functionGroups).forEach(([stepId, functions]) => {
      if (functions.length > 0) {
        const startLine = Math.min(...functions.map(f => f.startLine))
        const endLine = Math.max(...functions.map(f => f.endLine))
        const exactLines = functions.flatMap(f =>
          Array.from({ length: f.endLine - f.startLine + 1 }, (_, i) => f.startLine + i)
        )

        steps.push({
          id: stepId,
          label: stepLabels[stepId] || stepId,
          description: `${stepLabels[stepId]}相关代码`,
          startLine,
          endLine,
          exactLines,
          functions: functions.map(f => f.name)
        })
      }
    })

    // 按开始行号排序
    return steps.sort((a, b) => a.startLine - b.startLine)
  }

  /**
   * 提取函数调用关系
   */
  analyzeCallGraph() {
    const callGraph = new Map()

    this.functionMap.forEach((func, funcName) => {
      const calls = []
      const functionBody = this.lines.slice(func.startLine - 1, func.endLine).join('\n')

      // 查找函数调用模式
      const callPattern = /(\w+)\s*\(/g
      let match

      while ((match = callPattern.exec(functionBody)) !== null) {
        const calledFunction = match[1]
        // 排除内置函数和已定义的函数
        if (this.functionMap.has(calledFunction) && calledFunction !== funcName) {
          calls.push(calledFunction)
        }
      }

      callGraph.set(funcName, calls)
    })

    return callGraph
  }

  /**
   * 获取代码统计信息
   */
  getStatistics() {
    return {
      totalLines: this.lines.length,
      totalFunctions: this.functionMap.size,
      totalClasses: this.classMap.size,
      totalImports: this.imports.length,
      totalComments: this.comments.length,
      functionNames: this.getAllFunctionNames(),
      classNames: Array.from(this.classMap.keys())
    }
  }
}

/**
 * 创建代码分析器实例
 */
export function createCodeAnalyzer(code) {
  return new PythonCodeAnalyzer(code)
}

export default PythonCodeAnalyzer
