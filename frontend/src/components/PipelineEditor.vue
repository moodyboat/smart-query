<template>
  <div class="pipeline-editor">
    <!-- Pipeline List View -->
    <template v-if="!editingPipeline">
      <div class="pipeline-list-header">
        <div class="list-header-left">
          <span class="list-count">{{ filteredPipelines.length }} 个流程</span>
          <el-select v-model="filterDsId" placeholder="全部数据源" size="small" clearable style="width: 160px">
            <el-option v-for="ds in props.dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>
        </div>
        <el-button type="primary" size="small" @click="createPipeline">新建流程</el-button>
      </div>
      <div v-if="filteredPipelines.length === 0" class="empty-pipelines">
        <p>暂无流程，点击「新建流程」开始编排数据分析管道</p>
      </div>
      <div class="pipeline-grid">
        <div v-for="p in filteredPipelines" :key="p.id" class="pipeline-card" @click="openPipeline(p)">
          <div class="pipeline-card-header">
            <span class="pipeline-name">{{ p.name }}</span>
            <div style="display: flex; gap: 4px; align-items: center;">
              <el-tag v-if="p.sourceType === 'chat'" type="info" size="small" effect="plain">对话构建</el-tag>
              <el-tag :type="statusType(p.status)" size="small">{{ statusLabel(p.status) }}</el-tag>
            </div>
          </div>
          <div class="pipeline-card-meta">
            <span>{{ dataSourceName(p.dataSourceId) }}</span>
            <span>{{ nodeCount(p) }} 个步骤</span>
            <span>{{ formatDate(p.createdAt) }}</span>
          </div>
          <div v-if="parsedNodes(p).length" class="pipeline-card-flow">
            <span v-for="(n, i) in parsedNodes(p).slice(0, 5)" :key="i" class="mini-node">
              {{ nodeIcon(n.type) }} {{ nodeLabel(n) }}
              <span v-if="i < Math.min(parsedNodes(p).length, 5) - 1" class="mini-arrow">→</span>
            </span>
            <span v-if="parsedNodes(p).length > 5" class="mini-more">+{{ parsedNodes(p).length - 5 }}</span>
          </div>
          <div class="pipeline-card-actions">
            <el-button size="small" text type="primary" @click.stop="openPipeline(p)">编辑</el-button>
            <el-button size="small" text type="success" @click.stop="runFromCard(p)" :disabled="p.status === 'running'">
              {{ p.status === 'running' ? '运行中' : '运行' }}
            </el-button>
            <el-button size="small" text type="danger" @click.stop="handleDelete(p)">删除</el-button>
          </div>
        </div>
      </div>
    </template>

    <!-- Pipeline Editor View -->
    <template v-else>
      <div class="editor-toolbar">
        <el-button size="small" @click="closeEditor">← 返回</el-button>
        <el-input v-model="editingPipeline.name" size="small" style="width: 200px" />
        <div v-if="editingPipeline.sourceType === 'chat'" style="display: flex; align-items: center; gap: 4px;">
          <el-tag type="info" size="small" effect="plain">对话构建</el-tag>
        </div>
        <div v-if="linkedModel" style="display: flex; align-items: center; gap: 4px; margin-left: 8px;">
          <span style="font-size: 12px; color: var(--text-muted);">关联模型:</span>
          <el-tag size="small" type="success" style="cursor: pointer" @click="emit('goToModel', linkedModel.id)">{{ linkedModel.name }}</el-tag>
        </div>
        <el-tooltip v-if="syncStatus && !syncStatus.inSync" :content="syncStatus.reason || '模型与流程未同步'" placement="bottom">
          <el-tag type="warning" size="small" effect="plain" style="margin-left: 8px; cursor: pointer">未同步</el-tag>
        </el-tooltip>
        <div class="toolbar-actions">
          <el-button size="small" @click="savePipeline" :loading="saving">保存</el-button>
          <el-button size="small" type="primary" @click="runPipeline" :loading="running" :disabled="!canRun">
            {{ running ? '运行中...' : '▶ 运行' }}
          </el-button>
        </div>
      </div>

      <div class="editor-body">
        <!-- Algorithm Palette (Left) -->
        <div class="algorithm-palette">
          <div class="palette-title">算法库</div>
          <div v-for="group in algorithmGroups" :key="group.category" class="palette-group">
            <div class="palette-group-title">{{ group.category }}</div>
            <div
              v-for="algo in group.algorithms"
              :key="algo.algorithmId"
              class="palette-card"
              draggable="true"
              @dragstart="onPaletteDragStart($event, algo)"
            >
              <span class="palette-icon">{{ algo.icon || '🤖' }}</span>
              <div class="palette-info">
                <span class="palette-name">{{ algo.name }}</span>
                <span class="palette-types">{{ modelTypeNames(algo.modelTypes) }}</span>
              </div>
            </div>
          </div>
          <div class="palette-group">
            <div class="palette-group-title">基础节点</div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'data_source')">
              <span class="palette-icon">📥</span>
              <span class="palette-name">数据接入</span>
            </div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'preprocessing')">
              <span class="palette-icon">🔧</span>
              <span class="palette-name">预处理</span>
            </div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'fill_missing')">
              <span class="palette-icon">🩹</span>
              <span class="palette-name">填充缺失值</span>
            </div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'feature_engineering')">
              <span class="palette-icon">⚙️</span>
              <span class="palette-name">特征工程</span>
            </div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'evaluation')">
              <span class="palette-icon">📊</span>
              <span class="palette-name">模型评估</span>
            </div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'output')">
              <span class="palette-icon">💾</span>
              <span class="palette-name">输出写入</span>
            </div>
          </div>
        </div>

        <!-- Drop Canvas (Center) -->
        <div
          class="flow-canvas"
          @dragover.prevent
          @drop="onCanvasDrop"
          @click.self="selectedNodeId = null"
        >
          <div v-if="pipelineNodes.length === 0" class="canvas-empty">
            <p>拖拽左侧算法或节点到此处开始编排</p>
          </div>
          <div class="nodes-flow">
            <template v-for="(node, idx) in pipelineNodes" :key="node.id">
              <!-- Node Card -->
              <div
                class="flow-node"
                :class="[node.type, { selected: selectedNodeId === node.id, running: runningNodeId === node.id || previewingNodeId === node.id, done: doneNodeIds.has(node.id) }]"
                draggable="true"
                @dragstart="onNodeReorderStart($event, idx)"
                @dragend="onDragEnd"
                @click.stop="selectNode(node)"
              >
                <div class="node-color-bar"></div>
                <div class="node-body">
                  <div class="node-header">
                    <span class="node-icon">{{ nodeIcon(node.type) }}</span>
                    <span class="node-title">{{ node.config?.title || nodeTitle(node.type) }}</span>
                    <span :class="['node-status-dot', isNodeConfigured(node) ? 'configured' : 'unconfigured']"
                      :title="isNodeConfigured(node) ? '已配置' : '需要配置'"></span>
                    <el-dropdown trigger="click" @command="cmd => onNodeCmd(cmd, idx)" @click.stop size="small">
                      <el-button size="small" text class="node-more" @click.stop>⋯</el-button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item command="config">配置</el-dropdown-item>
                          <el-dropdown-item command="preview" :disabled="!isNodeConfigured(node) || previewingNodeId === node.id">
                            {{ previewingNodeId === node.id ? '运行中...' : '试运行' }}
                          </el-dropdown-item>
                          <el-dropdown-item command="rename">重命名</el-dropdown-item>
                          <el-dropdown-item command="delete" divided style="color: var(--danger)">删除</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </div>
                  <div class="node-summary">{{ nodeSummary(node) }}</div>
                  <div v-if="isNodeConfigured(node) || previewResult?.nodeId === node.id" class="node-actions-bar">
                    <el-button v-if="isNodeConfigured(node)" size="small" text type="primary"
                      :loading="previewingNodeId === node.id"
                      @click.stop="previewStep(node.id)">
                      {{ previewingNodeId === node.id ? '运行中...' : '▶ 试运行' }}
                    </el-button>
                    <el-button v-if="isNodeConfigured(node)" size="small" text type="info"
                      :loading="scriptLoading"
                      @click.stop="viewScript(node.id)">
                      查看脚本
                    </el-button>
                    <el-button v-if="previewResult?.nodeId === node.id && previewingNodeId !== node.id" size="small" text type="success" @click.stop="showPreviewPanel(node.id)">
                      查看结果
                    </el-button>
                  </div>
                </div>
              </div>

              <!-- Connection with drop zone -->
              <div
                class="flow-connector"
                @click="openAddStep(idx + 1)"
                @dragover.prevent="onConnectorDragOver($event)"
                @dragleave="onConnectorDragLeave($event)"
                @drop.stop="onConnectorDrop($event, idx + 1)"
              >
                <div class="connector-line"></div>
                <div class="connector-drop-hint">+</div>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- Execution Results Panel -->
      <div v-if="lastRunResult" class="run-results">
        <div class="results-header">
          <span class="results-title">执行结果</span>
          <el-tag :type="(lastRunResult.status === 'trained' || lastRunResult.status === 'success') ? 'success' : 'danger'" size="small">
            {{ (lastRunResult.status === 'trained' || lastRunResult.status === 'success') ? '训练成功' : '训练失败' }}
          </el-tag>
          <span v-if="lastRunResult.modelName" style="font-size:12px;color:var(--text-muted);margin-left:8px">模型: {{ lastRunResult.modelName }} (#{{ lastRunResult.modelId }})</span>
          <el-button size="small" text @click="lastRunResult = null" style="margin-left: auto">关闭</el-button>
        </div>
        <div v-if="lastRunResult.metrics" class="results-metrics">
          <template v-for="(val, key) in parseMetrics(lastRunResult.metrics)" :key="key">
            <div v-if="!key.startsWith('confusion_matrix') && !key.startsWith('class_labels')" class="result-metric">
              <span class="rm-value">{{ typeof val === 'number' ? formatMetricValue(key, val) : val }}</span>
              <span class="rm-label">{{ metricLabel(key) }}</span>
            </div>
          </template>
        </div>
        <div v-if="lastRunResult.modelType || lastRunResult.model_type" class="results-meta">
          <span>{{ algorithmLabel(lastRunResult.algorithm) }}</span>
          <span>·</span>
          <span>{{ modelTypeLabel(lastRunResult.modelType || lastRunResult.model_type) }}</span>
          <span>·</span>
          <span>表: {{ lastRunResult.sourceTable }}</span>
        </div>
        <div v-if="lastRunResult.output_table" class="results-meta" style="margin-top: 4px; color: var(--el-color-success)">
          <span>输出: {{ lastRunResult.output_table }} ({{ lastRunResult.output_rows || 0 }} 行)</span>
        </div>
        <div v-if="topFeatures.length" class="results-features">
          <span class="rf-title">Top 特征:</span>
          <div class="rf-bars">
            <div v-for="(f, i) in topFeatures" :key="i" class="rf-bar-row">
              <span class="rf-name">{{ f.name }}</span>
              <div class="rf-track"><div class="rf-fill" :style="{ width: (topFeatures[0].value ? (f.value / topFeatures[0].value * 100) : 0) + '%' }"></div></div>
              <span class="rf-val">{{ (f.value * 100).toFixed(1) }}%</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Node Preview Results Panel -->
      <el-drawer v-model="showPreviewDrawer" title="试运行结果" size="520px" direction="rtl" :modal="false">
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

          <!-- Preprocessing Preview -->
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
                <span :style="{ color: nc.after === 0 ? '#67c23a' : '#e6a23c' }">{{ nc.before }} → {{ nc.after }}</span>
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
                      <td :style="{ color: col.nullPct > 0 ? '#e6a23c' : 'inherit' }">{{ col.nullPct }}%</td>
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
                      :style="{ color: Math.abs(previewResult.correlations[fs.name]) > 0.3 ? '#e6a23c' : '#909399' }">
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
            <div v-if="previewResult.sampleRows?.length" class="preview-section">
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
            <div v-if="previewResult.featureImportance" class="preview-section">
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
      <el-drawer v-model="showScriptDrawer" title="Python 脚本" size="600px" direction="rtl" :modal="false">
        <div v-if="scriptLoading" style="text-align:center;padding:40px">
          <p style="color:var(--text-muted)">加载脚本中...</p>
        </div>
        <div v-else-if="scriptContent" class="script-viewer">
          <div class="script-toolbar">
            <el-button size="small" @click="copyScript">复制代码</el-button>
          </div>
          <pre class="script-code"><code>{{ scriptContent }}</code></pre>
        </div>
        <div v-else style="text-align:center;padding:40px;color:var(--text-muted)">
          请先配置该节点以生成脚本
        </div>
      </el-drawer>

      <!-- Node Config Panel -->
      <el-drawer v-model="showNodeConfig" :title="selectedNodeTitle" size="420px" direction="rtl" :modal="false">
        <div v-if="selectedNode" class="config-panel">
          <!-- Data Source Config -->
          <template v-if="selectedNode.type === 'data_source'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="数据表">
                <el-select v-model="selectedNode.config.table" placeholder="选择数据表" style="width: 100%"
                  :teleported="false" :loading="loadingTables" filterable @change="onTableSelected">
                  <el-option v-for="t in tableOptions" :key="t.name"
                    :label="t.comment ? `${t.name} (${t.comment})` : t.name" :value="t.name" />
                </el-select>
              </el-form-item>
              <el-form-item label="筛选条件">
                <el-input v-model="selectedNode.config.filter" placeholder="如: status = 1" />
              </el-form-item>
            </el-form>
            <div v-if="selectedNode.config.table" class="config-preview-section">
              <el-button size="small" :loading="dsPreviewLoading" @click="loadDsPreview">
                {{ dsPreviewRows.length ? '刷新预览' : '快速预览数据' }}
              </el-button>
              <div v-if="dsPreviewRows.length" class="ds-preview-wrap">
                <div class="preview-hint">共 {{ dsPreviewTotalRows }} 行 · 前 {{ dsPreviewRows.length }} 行预览</div>
                <el-table :data="dsPreviewRows" size="small" border stripe max-height="220" style="width: 100%">
                  <el-table-column v-for="col in dsPreviewColumns" :key="col" :prop="col" :label="col" min-width="90" show-overflow-tooltip />
                </el-table>
              </div>
              <div v-if="dsPreviewColumnStats.length" class="ds-column-stats">
                <div class="col-strategies-header">列统计</div>
                <div class="ds-stats-grid">
                  <div v-for="cs in dsPreviewColumnStats" :key="cs.name" class="ds-stat-card" :class="{ 'has-nulls': (cs.nulls || 0) > 0 }">
                    <div class="ds-stat-header">
                      <span class="ds-stat-name">{{ cs.name }}</span>
                      <span class="ds-stat-type">{{ cs.type }}</span>
                    </div>
                    <div v-if="(cs.nulls || 0) > 0" class="ds-stat-null">
                      <span class="text-danger">{{ cs.nulls }} 缺失 ({{ cs.nullPct }}%)</span>
                    </div>
                    <div v-else class="ds-stat-null">无缺失</div>
                    <div v-if="cs.min != null" class="ds-stat-range">
                      范围: {{ cs.min }} ~ {{ cs.max }} · 均值: {{ cs.avg }}
                    </div>
                    <div v-if="cs.unique != null" class="ds-stat-range">
                      {{ cs.unique }} 个唯一值
                      <span v-if="cs.topValues?.length"> · Top: <template v-for="(tv, i) in cs.topValues" :key="i">{{ i > 0 ? ', ' : '' }}{{ tv.val }}({{ tv.cnt }})</template></span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>

          <!-- Preprocessing Config -->
          <template v-if="selectedNode.type === 'preprocessing'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="缺失值处理">
                <el-select v-model="selectedNode.config.handleMissing" style="width: 100%" :teleported="false">
                  <el-option label="删除缺失行 (drop)" value="drop" />
                  <el-option label="填充均值 (mean)" value="fill_mean" />
                  <el-option label="填充中位数 (median)" value="fill_median" />
                  <el-option label="不处理" value="none" />
                </el-select>
              </el-form-item>
              <el-form-item label="分类编码">
                <el-select v-model="selectedNode.config.encoding" style="width: 100%" :teleported="false">
                  <el-option label="Label Encoding" value="label" />
                  <el-option label="One-Hot Encoding" value="onehot" />
                  <el-option label="不编码" value="none" />
                </el-select>
              </el-form-item>
              <el-form-item label="特征缩放">
                <el-select v-model="selectedNode.config.scaling" style="width: 100%" :teleported="false">
                  <el-option label="StandardScaler (标准化)" value="standard" />
                  <el-option label="MinMaxScaler (归一化)" value="minmax" />
                  <el-option label="不缩放" value="none" />
                </el-select>
              </el-form-item>
            </el-form>
            <div v-if="columnOptions.length" class="config-column-strategies">
              <div class="col-strategies-header">逐列缺失值策略</div>
              <div v-for="col in columnOptions" :key="col.name" class="col-strategy-row">
                <span class="col-strategy-name" :title="col.name">{{ col.name }}</span>
                <span class="col-strategy-type">{{ col.type }}</span>
                <el-select size="small" :model-value="getColumnStrategy(col.name)" style="flex: 1" :teleported="false"
                  @update:model-value="v => updateColumnStrategy(col.name, v)">
                  <el-option label="继承全局" value="inherit" />
                  <el-option label="删除缺失行" value="drop" />
                  <el-option label="填充均值" value="fill_mean" />
                  <el-option label="填充中位数" value="fill_median" />
                  <el-option label="填充众数" value="fill_mode" />
                  <el-option label="不处理" value="none" />
                </el-select>
              </div>
            </div>
          </template>

          <!-- Feature Engineering Config -->
          <template v-if="selectedNode.type === 'feature_engineering'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="目标列">
                <el-select v-model="selectedNode.config.targetColumn" placeholder="选择目标列" style="width: 100%"
                  :teleported="false" :disabled="!columnOptions.length" @change="onTargetColumnChange">
                  <el-option v-for="col in columnOptions" :key="col.name"
                    :label="`${col.name} (${col.type})`" :value="col.name" />
                </el-select>
                <div v-if="selectedNode.config.targetColumn" class="target-hint">
                  目标列已自动从特征列中排除
                </div>
              </el-form-item>
              <el-form-item label="特征列">
                <div v-if="columnOptions.length" class="column-picker">
                  <div class="column-list-header">
                    <el-checkbox v-model="featSelectAll" @change="onFeatSelectAll">全选</el-checkbox>
                    <span class="selected-count">已选 {{ selectedFeatCount }} / {{ columnOptions.length }} 列</span>
                  </div>
                  <div class="column-list">
                    <div v-for="col in columnOptions" :key="col.name" class="column-list-item"
                      :class="{ 'is-target': col.name === selectedNode.config.targetColumn }">
                      <el-checkbox
                        v-model="featChecked[col.name]"
                        @change="syncFeatCols"
                        :disabled="col.name === selectedNode.config.targetColumn">
                        <span class="col-name">{{ col.name }}</span>
                        <span class="col-type">{{ col.type }}</span>
                      </el-checkbox>
                      <span v-if="col.name === selectedNode.config.targetColumn" class="target-badge">目标</span>
                    </div>
                  </div>
                </div>
                <p v-else class="col-hint">请先配置数据接入节点</p>
              </el-form-item>
              <el-form-item label="特征分析">
                <el-button size="small" type="success" :loading="featAnalyzing" @click="analyzeFeatures"
                  :disabled="!selectedNode.config.targetColumn || selectedFeatCount === 0">
                  {{ featAnalysis ? '刷新分析' : '分析特征' }}
                </el-button>
                <div v-if="featAnalysis" class="feat-analysis-panel" style="margin-top: 8px">
                  <div class="preview-stats" style="margin-bottom: 8px">
                    <div class="preview-stat"><span class="ps-value">{{ featAnalysis.featureCount }}</span><span class="ps-label">特征数</span></div>
                    <div class="preview-stat"><span class="ps-value">{{ featAnalysis.sampleShape?.[0] }} × {{ featAnalysis.sampleShape?.[1] }}</span><span class="ps-label">矩阵形状</span></div>
                  </div>
                  <div v-if="featAnalysis.targetDistribution" class="target-dist">
                    <div class="preview-stat"><span class="ps-label">目标列分布</span></div>
                    <div v-for="(count, label) in featAnalysis.targetDistribution" :key="label" class="target-dist-item">
                      <span class="td-label">{{ label }}</span>
                      <span class="td-count">{{ count }} 条</span>
                    </div>
                  </div>
                  <div v-if="featAnalysis.correlations && Object.keys(featAnalysis.correlations).length" class="target-dist" style="margin-top: 6px">
                    <div class="preview-stat"><span class="ps-label">与目标相关性</span></div>
                    <div class="preview-tags" style="margin-top: 4px">
                      <el-tag v-for="col in Object.keys(featAnalysis.correlations)" :key="col" size="small"
                        :type="Math.abs(featAnalysis.correlations[col]) > 0.3 ? 'warning' : 'info'" class="feat-tag">
                        {{ col }}: {{ featAnalysis.correlations[col] > 0 ? '+' : '' }}{{ featAnalysis.correlations[col] }}
                      </el-tag>
                    </div>
                  </div>
                  <div v-if="featAnalysis.featureStats?.length" class="feature-stats-list">
                    <div v-for="fs in featAnalysis.featureStats" :key="fs.name" class="feature-stat-row"
                      :class="{ 'has-missing': fs.nullPct > 0 }">
                      <span class="fs-name">{{ fs.name }}</span>
                      <span class="fs-dtype">{{ fs.dtype }}</span>
                      <span class="fs-null" :class="{ 'text-danger': fs.nullPct > 0 }">{{ fs.nullPct }}% 缺失</span>
                      <span v-if="featAnalysis.correlations?.[fs.name] != null" class="fs-corr"
                        :style="{ color: Math.abs(featAnalysis.correlations[fs.name]) > 0.3 ? '#e6a23c' : '#909399', 'font-size': '11px' }">
                        r={{ featAnalysis.correlations[fs.name] > 0 ? '+' : '' }}{{ featAnalysis.correlations[fs.name] }}
                      </span>
                      <span v-if="fs.mean != null" class="fs-stat">均值: {{ fs.mean }} · 标准差: {{ fs.std }} · 范围: [{{ fs.min }}, {{ fs.max }}]</span>
                      <span v-else-if="fs.topValues" class="fs-stat">Top: <template v-for="(v, i) in Object.entries(fs.topValues).slice(0, 3)" :key="i"><span :class="i > 0 ? '' : ''">{{ i > 0 ? ', ' : '' }}{{ v[0] }}({{ v[1] }})</span></template> · {{ fs.unique }} 个唯一值</span>
                    </div>
                  </div>
                </div>
              </el-form-item>
              <el-form-item label="特征变换">
                <div class="transform-list">
                  <div v-for="(tf, i) in (selectedNode.config.transforms || [])" :key="i" class="transform-row" style="flex-wrap: wrap; gap: 4px;">
                    <el-select v-model="tf.type" style="width: 120px" size="small" :teleported="false" @change="onTransformTypeChange(tf)">
                      <el-option-group label="数值变换">
                        <el-option label="对数变换" value="log" />
                        <el-option label="多项式" value="polynomial" />
                        <el-option label="标准化" value="standardize" />
                        <el-option label="交互项" value="interaction" />
                      </el-option-group>
                      <el-option-group label="分箱">
                        <el-option label="等宽分箱" value="binning" />
                      </el-option-group>
                      <el-option-group label="编码">
                        <el-option label="目标编码" value="target_encode" />
                        <el-option label="频率编码" value="frequency_encode" />
                      </el-option-group>
                      <el-option-group label="日期">
                        <el-option label="日期提取" value="date_extract" />
                      </el-option-group>
                    </el-select>
                    <el-select v-model="tf.columns" multiple placeholder="选择列" style="flex: 1; min-width: 120px" size="small" :teleported="false" filterable>
                      <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
                    </el-select>
                    <!-- Polynomial degree -->
                    <el-input-number v-if="tf.type === 'polynomial'" v-model="tf.degree" :min="2" :max="4" size="small" style="width: 80px" placeholder="阶数" />
                    <!-- Binning config -->
                    <template v-if="tf.type === 'binning'">
                      <el-select v-model="tf.strategy" style="width: 100px" size="small" :teleported="false">
                        <el-option label="等宽" value="equal_width" />
                        <el-option label="等频" value="equal_freq" />
                        <el-option label="最优" value="optimal" />
                        <el-option label="自定义" value="custom" />
                      </el-select>
                      <el-input-number v-model="tf.bins" :min="2" :max="20" size="small" style="width: 70px" placeholder="箱数" />
                      <el-input v-if="tf.strategy === 'custom'" v-model="tf.edgesInput" placeholder="边界,如:0,30,60,100" size="small" style="width: 160px" @change="parseEdges(tf)" />
                    </template>
                    <!-- Date extract parts -->
                    <template v-if="tf.type === 'date_extract'">
                      <el-select v-model="tf.partsArr" multiple placeholder="提取部分" style="width: 180px" size="small" :teleported="false">
                        <el-option label="年" value="year" />
                        <el-option label="月" value="month" />
                        <el-option label="日" value="day" />
                        <el-option label="星期" value="weekday" />
                        <el-option label="季度" value="quarter" />
                      </el-select>
                    </template>
                    <el-button size="small" text type="danger" @click="removeTransform(i)">X</el-button>
                  </div>
                  <el-button size="small" type="primary" link @click="addTransform">+ 添加变换</el-button>
                </div>
              </el-form-item>
            </el-form>
          </template>

          <!-- Training Config -->
          <template v-if="selectedNode.type === 'training'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="模型类型">
                <el-select v-model="selectedNode.config.modelType" style="width: 100%" :teleported="false" @change="onModelTypeChange">
                  <el-option v-for="mt in modelTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="算法">
                <el-select v-model="selectedNode.config.algorithm" style="width: 100%" :teleported="false">
                  <el-option v-for="a in algoOptions" :key="a.value" :label="a.label" :value="a.value" />
                </el-select>
              </el-form-item>
              <el-divider content-position="left">超参数</el-divider>
              <div v-for="p in currentAlgoParams" :key="p.key" class="param-row">
                <label class="param-label">{{ p.label }} <span v-if="p.hint" class="param-hint">{{ p.hint }}</span></label>
                <el-input-number v-if="p.type === 'int'" v-model="selectedNode.config.hyperparams[p.key]"
                  :min="p.min" :max="p.max" :step="p.step || 1" size="small" style="width: 100%" />
                <el-input-number v-else-if="p.type === 'float'" v-model="selectedNode.config.hyperparams[p.key]"
                  :min="p.min" :max="p.max" :step="p.step || 0.1" :precision="2" size="small" style="width: 100%" />
                <el-select v-else-if="p.type === 'select'" v-model="selectedNode.config.hyperparams[p.key]"
                  size="small" style="width: 100%" :teleported="false">
                  <el-option v-for="o in p.options" :key="o" :label="o" :value="o" />
                </el-select>
              </div>
            </el-form>
          </template>

          <!-- Evaluation Config -->
          <template v-if="selectedNode.type === 'evaluation'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="验证模式">
                <el-select v-model="selectedNode.config.validationMode" style="width: 100%" :teleported="false" @change="val => { if (val !== 'temporal') selectedNode.config.temporalColumn = null }">
                  <el-option label="训练/测试分割" value="train_test" />
                  <el-option label="交叉验证" value="cv" />
                  <el-option label="样本外验证" value="oos" />
                  <el-option label="时间外验证" value="temporal" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="selectedNode.config.validationMode === 'temporal'" label="时间列">
                <el-select v-model="selectedNode.config.temporalColumn" placeholder="选择时间列" style="width: 100%" :teleported="false" filterable>
                  <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
                </el-select>
              </el-form-item>
              <el-form-item label="测试集比例">
                <el-slider v-model="selectedNode.config.testSize" :min="10" :max="40" :step="5"
                  :format-tooltip="v => v + '%'" />
              </el-form-item>
              <el-form-item label="交叉验证">
                <el-select v-model="selectedNode.config.cvFold" style="width: 100%" :teleported="false">
                  <el-option :label="'不使用'" :value="0" />
                  <el-option v-for="n in [3, 5, 10]" :key="n" :label="`${n}-Fold`" :value="n" />
                </el-select>
              </el-form-item>
            </el-form>
          </template>

          <!-- Fill Missing Config (Advanced) -->
          <template v-if="selectedNode.type === 'fill_missing'">
            <el-alert type="info" :closable="false" style="margin-bottom: 12px">
              高级缺失值处理：提供比"数据预处理"节点更精细的按列填充控制（支持固定值）。
              如已在预处理节点配置了缺失值策略，此节点可跳过。
            </el-alert>
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="填充策略">
                <el-select v-model="selectedNode.config.strategy" style="width: 100%" :teleported="false">
                  <el-option label="自动 (数值列填均值，分类列填众数)" value="auto" />
                  <el-option label="均值填充" value="mean" />
                  <el-option label="中位数填充" value="median" />
                  <el-option label="众数填充" value="mode" />
                  <el-option label="固定值" value="constant" />
                </el-select>
              </el-form-item>
              <el-form-item label="指定列">
                <el-select v-model="selectedNode.config.columns" multiple placeholder="留空=全部列" style="width: 100%" :teleported="false" filterable>
                  <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
                </el-select>
                <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">不选则对所有含缺失的列填充</div>
              </el-form-item>
            </el-form>
          </template>

          <!-- Output Config -->
          <template v-if="selectedNode.type === 'output'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="输出表名">
                <el-input v-model="selectedNode.config.table" placeholder="如: prediction_results" />
              </el-form-item>
              <el-form-item label="写入模式">
                <el-select v-model="selectedNode.config.mode" style="width: 100%" :teleported="false">
                  <el-option label="追加 (append)" value="append" />
                  <el-option label="替换 (replace)" value="replace" />
                </el-select>
              </el-form-item>
              <el-form-item label="自动建表">
                <el-switch v-model="selectedNode.config.autoCreate" active-text="表不存在时自动创建" />
              </el-form-item>
            </el-form>
          </template>

          <div class="config-footer">
            <el-button size="small" type="primary" @click="showNodeConfig = false">完成</el-button>
          </div>
        </div>
      </el-drawer>
    </template>

    <!-- Add Step Dialog -->
    <el-dialog v-model="showAddStep" title="添加步骤" width="440px" destroy-on-close>
      <div class="step-picker">
        <div v-for="t in stepTypes" :key="t.type" class="step-option" @click="addStep(insertIndex, t.type)">
          <span class="step-option-icon">{{ t.icon }}</span>
          <div class="step-option-info">
            <span class="step-option-title">{{ t.title }}</span>
            <span class="step-option-desc">{{ t.desc }}</span>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useMiningStore } from '../stores/mining'
import {
  fetchMiningPipelines, fetchMiningPipeline, createMiningPipeline,
  updateMiningPipeline, deleteMiningPipeline, executeMiningPipeline,
  validateMiningPipeline, previewStepPipeline, getStepScript, fetchDataSourceTables, fetchTableColumns,
  fetchTablePreview, fetchModelByPipeline, fetchPipelineSyncStatus, METRIC_NAMES
} from '../api'
import { useAlgorithms } from '../composables/useAlgorithms.js'
import { DEFAULT_MODEL_TYPE, DEFAULT_ALGORITHM } from '../constants'

const props = defineProps({
  dataSources: { type: Array, default: () => [] }
})

const {
  algorithms, modelTypes, algorithmGroups, loadAlgorithms,
  getAlgorithmLabel, getAlgorithmsForModelType,
  getAlgorithmParams, getDefaultHyperparams, getModelTypeLabel, modelTypeNames
} = useAlgorithms()

const emit = defineEmits(['close', 'goToModel'])
const miningStore = useMiningStore()

async function openPipelineById(id) {
  if (editingPipeline.value?.id === id) return
  const p = pipelines.value.find(x => x.id === id)
  if (p) { openPipeline(p); return }
  try {
    const fetched = await fetchMiningPipeline(id)
    if (fetched) openPipeline(fetched)
  } catch { /* not found */ }
}

defineExpose({ openPipelineById })

// Pipeline list
const pipelines = ref([])
const editingPipeline = ref(null)
const pipelineNodes = ref([])
const saving = ref(false)
const dirty = ref(false)
const running = ref(false)
const isDragging = ref(false)
const runningNodeId = ref(null)
const doneNodeIds = ref(new Set())
const filterDsId = ref(null)
const lastRunResult = ref(null)
const previewingNodeId = ref(null)
const previewResult = ref(null)
const showPreviewDrawer = ref(false)
const showScriptDrawer = ref(false)
const scriptContent = ref('')
const scriptLoading = ref(false)
const linkedModel = ref(null)
const syncStatus = ref(null)

// Quick data preview for data_source node
const dsPreviewLoading = ref(false)
const dsPreviewRows = ref([])
const dsPreviewColumns = ref([])
const dsPreviewTotalRows = ref(0)
const dsPreviewColumnStats = ref([])

// Per-column strategies for preprocessing node
const preprocessingColumns = ref([])

// Feature analysis for feature_engineering node
const featureStats = ref([])
const featureStatsLoading = ref(false)

// Node selection
const selectedNodeId = ref(null)
const showNodeConfig = ref(false)
const showAddStep = ref(false)
const insertIndex = ref(0)

// Table/Column selectors
const tableOptions = ref([])
const columnOptions = ref([])
const loadingTables = ref(false)
const featChecked = ref({})
const featSelectAll = ref(false)

// Step types
const stepTypes = [
  { type: 'data_source', icon: '📥', title: '数据接入', desc: '从数据库读取数据' },
  { type: 'preprocessing', icon: '🔧', title: '数据预处理', desc: '缺失值处理(含逐列策略)、编码、缩放' },
  { type: 'fill_missing', icon: '🩹', title: '高级缺失值处理', desc: '按列精细配置缺失值填充（预处理节点的增强版）' },
  { type: 'feature_engineering', icon: '⚙️', title: '特征工程', desc: '特征选择和目标定义' },
  { type: 'training', icon: '🧠', title: '模型训练', desc: '选择算法并训练模型' },
  { type: 'evaluation', icon: '📊', title: '模型评估', desc: '评估指标和验证策略' },
  { type: 'output', icon: '💾', title: '输出写入', desc: '将预测结果写入数据库表' }
]

// Selected node
const selectedNode = computed(() => {
  if (!selectedNodeId.value) return null
  return pipelineNodes.value.find(n => n.id === selectedNodeId.value)
})
const selectedNodeTitle = computed(() => {
  if (!selectedNode.value) return ''
  return selectedNode.value.config?.title || nodeTitle(selectedNode.value.type)
})

const algoOptions = computed(() => {
  const mt = selectedNode.value?.config?.modelType || DEFAULT_MODEL_TYPE
  return getAlgorithmsForModelType(mt).map(a => ({ value: a.algorithmId, label: (a.icon ? a.icon + ' ' : '') + a.name }))
})

function algorithmParams(algo) {
  return getAlgorithmParams(algo)
}

const currentAlgoParams = computed(() => {
  const algo = selectedNode.value?.config?.algorithm || firstAlgorithm()
  return algorithmParams(algo)
})

const selectedFeatCount = computed(() => Object.values(featChecked.value).filter(Boolean).length)

const featAnalyzing = ref(false)
const featAnalysis = ref(null)
async function analyzeFeatures() {
  if (!selectedNode.value || selectedNode.value.type !== 'feature_engineering') return
  featAnalyzing.value = true
  featAnalysis.value = null
  try {
    await savePipeline(true)
    const nodeId = selectedNode.value.id
    const res = await fetch(`/api/v1/mining/pipeline/${editingPipeline.value.id}/preview-step`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nodeId })
    })
    const json = await res.json()
    if (json.data?.status === 'success') {
      featAnalysis.value = json.data
    } else {
      ElMessage.error(json.data?.error || json.message || '分析失败')
    }
  } catch (e) {
    ElMessage.error('特征分析请求失败: ' + e.message)
  } finally {
    featAnalyzing.value = false
  }
}

const canRun = computed(() => {
  const dsNode = pipelineNodes.value.find(n => n.type === 'data_source')
  const hasTable = dsNode?.config?.table
  const hasTraining = pipelineNodes.value.some(n => n.type === 'training')
  const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
  let hasFeatures = false
  let hasTarget = false
  if (featNode?.config?.featureColumns) {
    try {
      const fc = featNode.config.featureColumns
      const arr = Array.isArray(fc) ? fc : JSON.parse(fc)
      hasFeatures = arr.length > 0
    } catch { hasFeatures = false }
  }
  hasTarget = !!featNode?.config?.targetColumn || !!pipelineNodes.value.find(n => n.type === 'training')?.config?.modelType?.includes('clustering')
  return pipelineNodes.value.length >= 3 && !!hasTable && hasTraining && hasFeatures && hasTarget
})

const filteredPipelines = computed(() => {
  if (!filterDsId.value) return pipelines.value
  return pipelines.value.filter(p => p.dataSourceId === filterDsId.value)
})

// Watch selected node -> open config drawer
watch(selectedNodeId, (id) => {
  if (id) {
    showNodeConfig.value = true
    loadTableAndColumns()
  }
})

// Close config drawer when drawer closes
watch(showNodeConfig, (v) => {
  if (!v) selectedNodeId.value = null
})

let skipDirtyWatch = false

watch(pipelineNodes, () => {
  if (editingPipeline.value && !skipDirtyWatch) dirty.value = true
}, { deep: true })

// Load pipelines
async function loadPipelines() {
  try {
    pipelines.value = await fetchMiningPipelines() || []
  } catch { pipelines.value = [] }
}

function parsedNodes(p) {
  try { return JSON.parse(p.nodes || '[]') } catch { return [] }
}

function nodeCount(p) { return parsedNodes(p).length }

function dataSourceName(dsId) {
  const ds = props.dataSources.find(d => d.id === dsId)
  return ds ? ds.name : '未知数据源'
}

function statusType(s) {
  return { draft: 'info', ready: '', running: 'warning', completed: 'success', failed: 'danger' }[s] || 'info'
}

function statusLabel(s) {
  return { draft: '草稿', ready: '就绪', running: '运行中', completed: '已完成', failed: '失败' }[s] || s
}

function formatDate(dt) {
  if (!dt) return ''
  return new Date(dt).toLocaleDateString('zh-CN')
}

function nodeIcon(type) {
  return { data_source: '📥', preprocessing: '🔧', fill_missing: '🩹', feature_engineering: '⚙️', training: '🧠', evaluation: '📊', output: '💾' }[type] || '📦'
}

function nodeTitle(type) {
  return { data_source: '数据接入', preprocessing: '数据预处理', fill_missing: '填充缺失值', feature_engineering: '特征工程', training: '模型训练', evaluation: '模型评估', output: '输出写入' }[type] || type
}

function nodeSummary(node) {
  try {
    const c = node.config || {}
    switch (node.type) {
    case 'data_source': return c.table ? `表: ${c.table}` + (c.filter ? ` | ${c.filter}` : '') : '未配置'
    case 'preprocessing': {
      const parts = []
      if (c.handleMissing && c.handleMissing !== 'none') parts.push(c.handleMissing === 'drop' ? '删除缺失' : '填充')
      if (c.encoding && c.encoding !== 'none') parts.push(c.encoding === 'label' ? 'Label编码' : 'One-Hot')
      if (c.scaling && c.scaling !== 'none') parts.push(c.scaling === 'standard' ? '标准化' : '归一化')
      return parts.length ? parts.join(' + ') : '未配置'
    }
    case 'fill_missing': {
      const strategy = { auto: '自动填充', mean: '均值', median: '中位数', mode: '众数', constant: '固定值' }[c.strategy] || c.strategy || 'auto'
      return c.columns?.length ? `${strategy} (${c.columns.length}列)` : strategy
    }
    case 'feature_engineering': {
      let fc = []
      try {
        fc = c.featureColumns ? (Array.isArray(c.featureColumns) ? c.featureColumns : JSON.parse(c.featureColumns)) : []
      } catch { fc = [] }
      const tfCount = Array.isArray(c.transforms) ? c.transforms.length : 0
      const parts = []
      if (fc.length) parts.push(`${fc.length} 列特征`)
      if (c.targetColumn) parts.push(`→ ${c.targetColumn}`)
      if (tfCount) parts.push(`${tfCount} 变换`)
      return parts.length ? parts.join(' ') : '未配置'
    }
    case 'training': {
      return c.algorithm ? getAlgorithmLabel(c.algorithm) : '未配置'
    }
    case 'evaluation': {
      const vm = c.validationMode
      if (vm === 'temporal') return `时间外验证 (${c.temporalColumn || '?'})`
      if (vm === 'cv') return `${c.cvFold || 5}-Fold CV`
      if (vm === 'oos') return `OOS ${c.cvFold || 5}-Fold + 测试集 ${c.testSize || 20}%`
      return `测试集 ${c.testSize || 20}%`
    }
    case 'output': return c.table ? `→ ${c.table}` + (c.mode === 'replace' ? ' (替换)' : '') : '未配置'
    default: return ''
  }
  } catch { return '' }
}

function defaultNodeConfig(type) {
  switch (type) {
    case 'data_source': return { title: '数据接入', table: '', filter: '' }
    case 'preprocessing': return { title: '数据预处理', handleMissing: 'drop', encoding: 'label', scaling: 'standard' }
    case 'fill_missing': return { title: '填充缺失值', strategy: 'auto', columns: [], fillValues: {} }
    case 'feature_engineering': return { title: '特征工程', featureColumns: '[]', targetColumn: '' }
    case 'training': return { title: '模型训练', modelType: firstModelType(), algorithm: firstAlgorithm(), hyperparams: {} }
    case 'evaluation': return { title: '模型评估', testSize: 20, cvFold: 0, validationMode: 'train_test', temporalColumn: null }
    case 'output': return { title: '输出写入', table: '', mode: 'append', autoCreate: false }
    default: return { title: type }
  }
}

function firstModelType() {
  return modelTypes.value.length > 0 ? modelTypes.value[0].id : DEFAULT_MODEL_TYPE
}

function firstAlgorithm() {
  return algorithms.value.length > 0 ? algorithms.value[0].algorithmId : DEFAULT_ALGORITHM
}

function isNodeConfigured(node) {
  const c = node.config || {}
  switch (node.type) {
    case 'data_source': return !!c.table
    case 'preprocessing': return true
    case 'fill_missing': return true
    case 'feature_engineering': {
      try {
        const fc = c.featureColumns ? (typeof c.featureColumns === 'string' ? JSON.parse(c.featureColumns) : c.featureColumns) : []
        return fc.length > 0
      } catch { return false }
    }
    case 'training': return !!c.algorithm
    case 'evaluation': return true
    case 'output': return !!c.table
    default: return true
  }
}

// Pipeline CRUD
async function createPipeline() {
  const ds = props.dataSources.find(d => !d.system) || props.dataSources[0]
  if (!ds) { ElMessage.warning('请先配置数据源'); return }

  const defaultNodes = [
    { id: 'n1', type: 'data_source', config: { ...defaultNodeConfig('data_source') } },
    { id: 'n2', type: 'preprocessing', config: { ...defaultNodeConfig('preprocessing') } },
    { id: 'n3', type: 'feature_engineering', config: { ...defaultNodeConfig('feature_engineering') } },
    { id: 'n4', type: 'training', config: { ...defaultNodeConfig('training') } },
    { id: 'n5', type: 'evaluation', config: { ...defaultNodeConfig('evaluation') } },
    { id: 'n6', type: 'output', config: { ...defaultNodeConfig('output') } }
  ]
  const defaultEdges = [
    { source: 'n1', target: 'n2' }, { source: 'n2', target: 'n3' },
    { source: 'n3', target: 'n4' }, { source: 'n4', target: 'n5' },
    { source: 'n5', target: 'n6' }
  ]

  try {
    const p = await createMiningPipeline({
      name: '新数据分析流程',
      dataSourceId: ds.id,
      nodes: defaultNodes,
      edges: defaultEdges
    })
    pipelines.value.unshift(p)
    openPipeline(p)

    // Auto-configure: load first available table and pre-fill nodes
    try {
      const tables = await fetchDataSourceTables(ds.id) || []
      if (tables.length > 0) {
        const firstTable = tables[0].name
        const dsNode = pipelineNodes.value.find(n => n.type === 'data_source')
        if (dsNode) dsNode.config.table = firstTable

        const columns = await fetchTableColumns(ds.id, firstTable) || []
        columnOptions.value = columns
        autoConfigureFeatures(columns, firstTable)
      }
    } catch { /* auto-config is best-effort */ }

    ElMessage.success('已创建并预配置')
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.message || ''))
  }
}

function autoConfigureFeatures(columns, tableName) {
  const skipCols = new Set(['id', 'created_at', 'updated_at', 'updated_by'])
  const numericTypes = new Set(['int', 'bigint', 'tinyint', 'smallint', 'float', 'double', 'decimal', 'numeric'])
  const labelTypes = new Set(['tinyint'])

  // Find potential target: prefer tinyint (binary label) or last enum column
  let targetCol = null
  const featureCols = []
  const labelSuffixes = ['_label', '_flag', '_target', '_class', '_status', '_type']

  for (const col of columns) {
    const name = col.name.toLowerCase()
    if (skipCols.has(name) || name.endsWith('_id') || name.endsWith('_at') || name === tableName + '_id') continue

    const type = (col.type || '').toLowerCase()

    if (!targetCol) {
      // Prefer columns that look like labels
      if (labelSuffixes.some(s => name.includes(s)) || labelTypes.has(type)) {
        targetCol = col.name
        continue
      }
    }
    featureCols.push(col.name)
  }

  // If no label-like column found, use last enum column or first tinyint
  if (!targetCol) {
    const enumCol = columns.find(c => (c.type || '').toLowerCase().startsWith('enum') && !skipCols.has(c.name.toLowerCase()))
    if (enumCol) targetCol = enumCol.name
  }

  const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
  if (featNode) {
    const feats = targetCol ? featureCols.filter(c => c !== targetCol) : featureCols
    featNode.config.featureColumns = JSON.stringify(feats)
    featNode.config.targetColumn = targetCol || ''
    // Init checkboxes
    const checked = {}
    columns.forEach(c => {
      checked[c.name] = feats.includes(c.name) && c.name !== targetCol
    })
    featChecked.value = checked
  }

  // Auto-configure output table name
  const outNode = pipelineNodes.value.find(n => n.type === 'output')
  if (outNode && tableName) {
    outNode.config.table = tableName + '_prediction_result'
  }
}

function openPipeline(p) {
  skipDirtyWatch = true
  editingPipeline.value = { ...p }
  pipelineNodes.value = normalizeNodes(parsedNodes(p))
  selectedNodeId.value = null
  showNodeConfig.value = false
  runningNodeId.value = null
  doneNodeIds.value = new Set()
  linkedModel.value = null
  syncStatus.value = null
  dirty.value = false
  nextTick(() => { skipDirtyWatch = false })
  fetchModelByPipeline(p.id).then(m => { linkedModel.value = m }).catch(() => {})
  fetchPipelineSyncStatus(p.id).then(s => { syncStatus.value = s }).catch(() => {})
  loadTableAndColumns()
}

function normalizeNodes(nodes) {
  return nodes.map(n => {
    const config = { ...n.config }
    // Normalize hyperparameters → hyperparams for training nodes
    if (n.type === 'training' && config.hyperparameters && !config.hyperparams) {
      config.hyperparams = config.hyperparameters
      delete config.hyperparameters
    }
    // Ensure hyperparams exists
    if (n.type === 'training' && !config.hyperparams) {
      config.hyperparams = {}
    }
    // Normalize featureColumns: array → JSON string for consistency
    if (n.type === 'feature_engineering' && Array.isArray(config.featureColumns)) {
      config.featureColumns = JSON.stringify(config.featureColumns)
    }
    // Ensure transforms is a reactive array for feature_engineering nodes
    if (n.type === 'feature_engineering') {
      if (!config.transforms) config.transforms = []
      else if (!Array.isArray(config.transforms)) config.transforms = []
      // Normalize date_extract partsArr for UI
      for (const tf of config.transforms) {
        if (tf.type === 'date_extract' && tf.parts && !tf.partsArr) {
          tf.partsArr = tf.parts.split(',').map(s => s.trim()).filter(Boolean)
        }
        if (tf.type === 'binning' && tf.edges && !tf.edgesInput) {
          tf.edgesInput = tf.edges.join(',')
        }
      }
    }
    return { ...n, config }
  })
}

async function runFromCard(p) {
  openPipeline(p)
  await loadTableAndColumns()
  await nextTick()
  if (canRun.value) runPipeline()
  else ElMessage.warning('流程未配置完整，无法运行')
}

function closeEditor() {
  const doClose = () => {
    editingPipeline.value = null
    pipelineNodes.value = []
    selectedNodeId.value = null
    showNodeConfig.value = false
    dirty.value = false
  }
  if (dirty.value) {
    ElMessageBox.confirm('您有未保存的更改，确定要离开吗？', '未保存的更改', {
      confirmButtonText: '离开', cancelButtonText: '取消', type: 'warning'
    }).then(doClose).catch(() => {})
  } else {
    doClose()
  }
}

async function savePipeline(silent = false) {
  if (!editingPipeline.value) return
  saving.value = true
  try {
    // Work on a deep copy to avoid mutating reactive state on save failure
    const nodesToSave = JSON.parse(JSON.stringify(pipelineNodes.value))
    for (const node of nodesToSave) {
      if (node.type === 'training' && node.config?.hyperparams) {
        const params = algorithmParams(node.config.algorithm)
        for (const p of params) {
          if (p.type === 'float' && typeof node.config.hyperparams[p.key] === 'number') {
            node.config.hyperparams[p.key] = Math.round(node.config.hyperparams[p.key] * 10000) / 10000
          }
        }
      }
      if (node.type === 'feature_engineering' && node.config?.transforms) {
        for (const tf of node.config.transforms) {
          if (tf.type === 'date_extract' && tf.partsArr) tf.parts = tf.partsArr.join(',')
          if (tf.type === 'binning' && tf.strategy === 'custom' && tf.edgesInput) {
            tf.edges = tf.edgesInput.split(',').map(v => parseFloat(v.trim())).filter(v => !isNaN(v))
          }
        }
      }
    }
    const edges = []
    for (let i = 0; i < nodesToSave.length - 1; i++) {
      edges.push({ source: nodesToSave[i].id, target: nodesToSave[i + 1].id })
    }
    await updateMiningPipeline(editingPipeline.value.id, {
      name: editingPipeline.value.name,
      nodes: nodesToSave,
      edges: edges,
      status: canRun.value ? 'ready' : 'draft'
    })

    // Validate after save
    try {
      const vr = await validateMiningPipeline(editingPipeline.value.id)
      if (!vr.valid) {
        if (!silent) ElMessage.warning('流水线校验未通过: ' + (vr.errors || []).join('; '))
      } else if (vr.warnings && vr.warnings.length > 0) {
        if (!silent) ElMessage.success('已保存（有警告: ' + vr.warnings.join('; ') + '）')
      } else {
        if (!silent) ElMessage.success('已保存')
      }
    } catch {
      if (!silent) ElMessage.success('已保存')
    }
    await loadPipelines()
    if (editingPipeline.value?.id) {
      fetchPipelineSyncStatus(editingPipeline.value.id).then(s => { syncStatus.value = s }).catch(() => {})
    }
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  } finally {
    saving.value = false
    dirty.value = false
  }
}

async function runPipeline() {
  if (!editingPipeline.value || !canRun.value || running.value) return
  try {
    await savePipeline(true)
  } catch (e) {
    ElMessage.error('保存流程失败，无法执行: ' + (e.message || ''))
    return
  }
  running.value = true
  runningNodeId.value = null
  doneNodeIds.value = new Set()
  lastRunResult.value = null

  try {
    // Start backend execution in parallel with animation
    let backendDone = false
    const executePromise = executeMiningPipeline(editingPipeline.value.id)
      .then(r => { backendDone = true; return r })
      .catch(e => { backendDone = true; throw e })

    // Animate through nodes while backend executes
    const nodeCount = pipelineNodes.value.length
    const animDelay = Math.max(400, Math.min(1200, 6000 / nodeCount))
    for (let i = 0; i < nodeCount; i++) {
      if (backendDone) {
        // Backend finished early — mark remaining nodes done instantly
        for (let j = i; j < nodeCount; j++) doneNodeIds.value.add(pipelineNodes.value[j].id)
        runningNodeId.value = null
        break
      }
      runningNodeId.value = pipelineNodes.value[i].id
      await Promise.race([
        new Promise(r => setTimeout(r, animDelay)),
        executePromise.then(() => {}).catch(() => {}) // resolves when backend finishes
      ])
      doneNodeIds.value.add(pipelineNodes.value[i].id)
    }
    runningNodeId.value = null

    // Wait for backend execution to complete (may already be done)
    const result = await executePromise
    lastRunResult.value = result

    await loadPipelines()
    miningStore.loadModels()

    // Refresh linked model after execution
    fetchModelByPipeline(editingPipeline.value.id).then(m => { linkedModel.value = m }).catch(() => {})
    fetchPipelineSyncStatus(editingPipeline.value.id).then(s => { syncStatus.value = s }).catch(() => {})

    // Show success notification with key metrics
    const metrics = parseMetrics(result.metrics)
    const primaryKey = result.modelType === 'regression' ? 'test_r2' : 'test_accuracy'
    const primary = metrics[primaryKey] ?? metrics[primaryKey.replace('test_', '')]
    const parts = [`模型 #${result.modelId}`]
    if (primary != null) parts.push(`${metricLabel(primaryKey)} ${primary < 10 ? primary.toFixed(4) : (primary * 100).toFixed(1) + '%'}`)
    if (metrics.overfitting_gap != null) parts.push(`过拟合差距 ${(metrics.overfitting_gap * 100).toFixed(1)}%`)
    ElMessage.success(`流程执行完成 — ${parts.join('，')}`)
  } catch (e) {
    try { await updateMiningPipeline(editingPipeline.value.id, { status: 'failed' }) } catch {}
    ElMessage.error('执行失败: ' + (e.message || '未知错误'))
  } finally {
    running.value = false
    runningNodeId.value = null
  }
}

async function handleDelete(p) {
  try {
    await ElMessageBox.confirm(`确定删除流程「${p.name}」？`, '删除流程', { type: 'warning' })
    await deleteMiningPipeline(p.id)
    pipelines.value = pipelines.value.filter(x => x.id !== p.id)
    ElMessage.success('已删除')
  } catch { /* cancelled */ }
}

// Node actions
function openAddStep(idx) {
  insertIndex.value = idx
  showAddStep.value = true
}

function addStep(idx, type) {
  const id = 'n_' + Math.random().toString(36).slice(2, 10)
  const node = { id, type, config: defaultNodeConfig(type) }
  pipelineNodes.value.splice(idx, 0, node)
  showAddStep.value = false
}

function onNodeCmd(cmd, idx) {
  if (cmd === 'config') {
    selectedNodeId.value = pipelineNodes.value[idx].id
  } else if (cmd === 'preview') {
    previewStep(pipelineNodes.value[idx].id)
  } else if (cmd === 'rename') {
    const node = pipelineNodes.value[idx]
    ElMessageBox.prompt('步骤名称:', '重命名', {
      inputValue: node.config?.title || nodeTitle(node.type),
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    }).then(({ value }) => {
      if (value && node.config) node.config.title = value
    }).catch(() => {})
  } else if (cmd === 'delete') {
    const deletedId = pipelineNodes.value[idx]?.id
    pipelineNodes.value.splice(idx, 1)
    if (selectedNodeId.value === deletedId) {
      selectedNodeId.value = null
      showNodeConfig.value = false
    }
  }
}

// Table & Column loading
async function loadTableAndColumns() {
  if (!editingPipeline.value) return
  const dsId = editingPipeline.value.dataSourceId
  if (!dsId) return

  // Load tables
  loadingTables.value = true
  try { tableOptions.value = await fetchDataSourceTables(dsId) || [] }
  catch { tableOptions.value = [] }
  finally { loadingTables.value = false }

  // Load columns if table is set
  const dsNode = pipelineNodes.value.find(n => n.type === 'data_source')
  if (dsNode?.config?.table) {
    await loadColumns(dsNode.config.table)
  }
}

async function onTableSelected(tableName) {
  await loadColumns(tableName)
  const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
  if (featNode) {
    featNode.config.featureColumns = '[]'
    featNode.config.targetColumn = ''
    featChecked.value = {}
  }
}

async function loadColumns(tableName) {
  if (!editingPipeline.value?.dataSourceId || !tableName) { columnOptions.value = []; return }
  try {
    columnOptions.value = await fetchTableColumns(editingPipeline.value.dataSourceId, tableName) || []
    // Init feature checkboxes
    const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
    if (featNode) {
      const raw = featNode.config?.featureColumns
      const saved = raw ? (Array.isArray(raw) ? raw : JSON.parse(raw)) : []
      const checked = {}
      columnOptions.value.forEach(c => { checked[c.name] = saved.includes(c.name) })
      featChecked.value = checked
      syncFeatCols()
    }
  } catch { columnOptions.value = [] }
}

function onFeatSelectAll(val) {
  const checked = {}
  const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
  const target = featNode?.config?.targetColumn
  columnOptions.value.forEach(c => {
    if (val && c.name === target) {
      checked[c.name] = false
    } else {
      checked[c.name] = val
    }
  })
  featChecked.value = checked
  syncFeatCols()
}

function syncFeatCols() {
  const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
  if (featNode) {
    const target = featNode.config.targetColumn
    const cols = Object.entries(featChecked.value)
      .filter(([, v]) => v)
      .map(([k]) => k)
      .filter(c => c !== target)
    featNode.config.featureColumns = JSON.stringify(cols)
  }
  autoAnalyzeDebounced()
}

function onTargetColumnChange(target) {
  const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
  if (!featNode) return
  // Auto-uncheck target from features
  if (target && featChecked.value[target]) {
    featChecked.value[target] = false
  }
  syncFeatCols()
}

let _autoAnalyzeTimer = null
function autoAnalyzeDebounced() {
  if (_autoAnalyzeTimer) clearTimeout(_autoAnalyzeTimer)
  _autoAnalyzeTimer = setTimeout(() => {
    const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
    if (!featNode) return
    const hasTarget = !!featNode.config.targetColumn
    let hasFeatures = false
    try {
      const fc = featNode.config.featureColumns
      const arr = Array.isArray(fc) ? fc : JSON.parse(fc)
      hasFeatures = arr.length > 0
    } catch { hasFeatures = false }
    if (hasTarget && hasFeatures && !featAnalyzing.value) {
      analyzeFeatures()
    }
  }, 1500)
}

function addTransform() {
  if (!selectedNode.value) return
  const transforms = selectedNode.value.config.transforms || []
  transforms.push({ type: 'log', columns: [], degree: 2, bins: 5, strategy: 'equal_width', edges: [], edgesInput: '', parts: 'year,month,day', partsArr: ['year', 'month', 'day'] })
  selectedNode.value.config.transforms = [...transforms]
}

function onTransformTypeChange(tf) {
  if (tf.type === 'binning') {
    tf.strategy = tf.strategy || 'equal_width'
    tf.bins = tf.bins || 5
  }
  if (tf.type === 'date_extract') {
    tf.partsArr = tf.partsArr || ['year', 'month', 'day']
    tf.parts = tf.parts || 'year,month,day'
  }
}

function parseEdges(tf) {
  if (tf.edgesInput) {
    tf.edges = tf.edgesInput.split(',').map(v => parseFloat(v.trim())).filter(v => !isNaN(v))
  } else {
    tf.edges = []
  }
}

function removeTransform(idx) {
  if (!selectedNode.value) return
  const transforms = [...(selectedNode.value.config.transforms || [])]
  transforms.splice(idx, 1)
  selectedNode.value.config.transforms = transforms
}

// Per-column missing value strategy helpers
function getColumnStrategy(colName) {
  return selectedNode.value?.config?.columnStrategies?.[colName] || 'inherit'
}

function updateColumnStrategy(colName, value) {
  if (!selectedNode.value) return
  const config = { ...selectedNode.value.config }
  const current = { ...(config.columnStrategies || {}) }
  if (value === 'inherit') {
    delete current[colName]
  } else {
    current[colName] = value
  }
  if (Object.keys(current).length) {
    config.columnStrategies = current
  } else {
    delete config.columnStrategies
  }
  selectedNode.value.config = config
}

// Quick data source preview (direct SQL, no Python)
async function loadDsPreview() {
  const dsId = editingPipeline.value?.dataSourceId
  const tableName = selectedNode.value?.config?.table
  if (!dsId || !tableName) return
  dsPreviewLoading.value = true
  try {
    const result = await fetchTablePreview(dsId, tableName, 20)
    dsPreviewColumns.value = result.columns || []
    dsPreviewRows.value = result.rows || []
    dsPreviewTotalRows.value = result.totalCount || 0
    dsPreviewColumnStats.value = result.columnStats || []
  } catch {
    dsPreviewRows.value = []
    dsPreviewColumns.value = []
    dsPreviewTotalRows.value = 0
    dsPreviewColumnStats.value = []
  } finally {
    dsPreviewLoading.value = false
  }
}

// Preview step
async function previewStep(nodeId) {
  if (!editingPipeline.value || previewingNodeId.value) return
  previewingNodeId.value = nodeId
  previewResult.value = null
  try {
    await savePipeline()
    const result = await previewStepPipeline(editingPipeline.value.id, nodeId)
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

function showPreviewPanel(nodeId) {
  showPreviewDrawer.value = true
}

async function viewScript(nodeId) {
  if (!editingPipeline.value || scriptLoading.value) return
  scriptLoading.value = true
  scriptContent.value = ''
  try {
    await savePipeline()
    const result = await getStepScript(editingPipeline.value.id, nodeId)
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

const topPreviewFeatures = computed(() => {
  if (!previewResult.value?.featureImportance) return []
  const fi = previewResult.value.featureImportance
  return Object.entries(fi)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10)
    .map(([name, value]) => ({ name, value }))
})

function onModelTypeChange() {
  if (!selectedNode.value) return
  const c = selectedNode.value.config
  const available = getAlgorithmsForModelType(c.modelType)
  c.algorithm = available.length ? available[0].algorithmId : firstAlgorithm()
  const defaults = getDefaultHyperparams(c.algorithm)
  c.hyperparams = { ...defaults }
}

// Init
loadAlgorithms().catch(() => {})
loadPipelines().catch(() => {})

// Drag and Drop handlers
function onPaletteDragStart(event, algo, nodeType) {
  const data = algo
    ? { type: 'algorithm', algorithmId: algo.algorithmId, name: algo.name, modelTypes: algo.modelTypes }
    : { type: 'node', nodeType }
  event.dataTransfer.setData('application/json', JSON.stringify(data))
  event.dataTransfer.effectAllowed = 'copy'
}

function onNodeReorderStart(event, idx) {
  isDragging.value = true
  event.dataTransfer.setData('application/json', JSON.stringify({ type: 'reorder', fromIdx: idx }))
  event.dataTransfer.effectAllowed = 'move'
}

function onDragEnd() {
  isDragging.value = false
}

function onCanvasDrop(event) {
  const raw = event.dataTransfer.getData('application/json')
  if (!raw) return
  let data
  try { data = JSON.parse(raw) } catch { return }

  // Find closest insertion index based on drop position
  const insertIdx = findClosestInsertIndex(event.clientX, event.clientY)

  if (data.type === 'algorithm') {
    addAlgorithmNode(data, insertIdx)
  } else if (data.type === 'node') {
    addStep(insertIdx, data.nodeType)
  } else if (data.type === 'reorder') {
    reorderNode(data.fromIdx, insertIdx)
  }
}

function findClosestInsertIndex(clientX, clientY) {
  const nodes = document.querySelectorAll('.flow-node')
  if (nodes.length === 0) return 0
  let closest = nodes.length
  let minDist = Infinity
  nodes.forEach((el, i) => {
    const rect = el.getBoundingClientRect()
    const midY = rect.top + rect.height / 2
    const dist = Math.abs(clientY - midY)
    if (dist < minDist) {
      minDist = dist
      closest = clientY < midY ? i : i + 1
    }
  })
  return closest
}

function onConnectorDrop(event, insertIdx) {
  event.stopPropagation()
  event.currentTarget?.classList?.remove('connector-drag-over')
  const raw = event.dataTransfer.getData('application/json')
  if (!raw) return
  let data
  try { data = JSON.parse(raw) } catch { return }
  if (data.type === 'algorithm') {
    addAlgorithmNode(data, insertIdx)
  } else if (data.type === 'node') {
    addStep(insertIdx, data.nodeType)
  } else if (data.type === 'reorder') {
    reorderNode(data.fromIdx, insertIdx)
  }
}

function onConnectorDragOver(event) {
  event.currentTarget?.classList?.add('connector-drag-over')
}

function onConnectorDragLeave(event) {
  event.currentTarget?.classList?.remove('connector-drag-over')
}

function addAlgorithmNode(algoData, idx) {
  const id = 'n_' + Math.random().toString(36).slice(2, 10)
  const types = parseJson(algoData.modelTypes, [DEFAULT_MODEL_TYPE])
  const defaults = getDefaultHyperparams(algoData.algorithmId)
  const node = {
    id,
    type: 'training',
    config: {
      title: algoData.name,
      modelType: types[0],
      algorithm: algoData.algorithmId,
      hyperparams: { ...defaults }
    }
  }
  pipelineNodes.value.splice(idx, 0, node)
}

function reorderNode(fromIdx, toIdx) {
  if (fromIdx === toIdx || fromIdx === toIdx - 1) return
  const [node] = pipelineNodes.value.splice(fromIdx, 1)
  const insertAt = fromIdx < toIdx ? toIdx - 1 : toIdx
  pipelineNodes.value.splice(insertAt, 0, node)
}

function selectNode(node) {
  if (isDragging.value) return
  selectedNodeId.value = node.id
  showNodeConfig.value = true
}

function parseJson(val, fallback) {
  if (!val) return fallback
  if (typeof val === 'string') { try { return JSON.parse(val) } catch { return fallback } }
  return val
}

function nodeLabel(node) {
  if (node.type === 'training' && node.config?.algorithm) {
    return getAlgorithmLabel(node.config.algorithm)
  }
  return nodeTitle(node.type)
}

function parseMetrics(json) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || {} } catch { return {} }
}

function metricLabel(key) {
  return METRIC_NAMES[key] || key
}

const PERCENT_METRICS = new Set([
  'accuracy', 'precision', 'recall', 'f1',
  'train_accuracy', 'test_accuracy', 'train_f1', 'test_f1',
  'cv_mean', 'overfitting_gap'
])

function formatMetricValue(key, val) {
  if (PERCENT_METRICS.has(key)) {
    return (val * 100).toFixed(1) + '%'
  }
  if (key === 'silhouette_score' || key === 'silhouette') {
    return val.toFixed(4)
  }
  return val < 10 ? val.toFixed(4) : val.toFixed(2)
}

const topFeatures = computed(() => {
  const fi = lastRunResult.value?.featureImportance || lastRunResult.value?.feature_importance
  if (!fi) return []
  const parsed = parseMetrics(fi)
  return Object.entries(parsed)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([name, value]) => ({ name, value }))
})

const algorithmLabel = getAlgorithmLabel
const modelTypeLabel = getModelTypeLabel
</script>

<style scoped>
.pipeline-editor {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.pipeline-list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-lg);
}

.list-header-left {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.list-count {
  color: var(--text-muted);
  font-size: var(--font-md);
}

.empty-pipelines {
  text-align: center;
  padding: var(--space-2xl) 0;
  color: var(--text-muted);
}

.pipeline-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-lg);
}

.pipeline-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  padding: var(--space-lg);
  cursor: pointer;
  transition: all 0.2s;
}

.pipeline-card:hover {
  box-shadow: var(--shadow-md);
  border-color: var(--primary);
}

.pipeline-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-sm);
}

.pipeline-name {
  font-weight: 600;
  font-size: var(--font-lg);
  color: var(--text-primary);
  cursor: pointer;
  transition: color 0.15s;
}
.pipeline-name:hover {
  color: var(--primary);
}

.pipeline-card-meta {
  display: flex;
  gap: var(--space-md);
  color: var(--text-muted);
  font-size: var(--font-sm);
  margin-bottom: var(--space-sm);
}

.pipeline-card-flow {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  padding: var(--space-sm) 0;
  border-top: 1px solid var(--border);
  font-size: var(--font-sm);
  color: var(--text-secondary);
}

.mini-arrow,
.mini-more {
  color: var(--text-muted);
}

.pipeline-card-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: var(--space-sm);
  border-top: 1px solid var(--border);
}

/* Editor Toolbar */
.editor-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding-bottom: var(--space-lg);
  border-bottom: 1px solid var(--border);
  margin-bottom: var(--space-xl);
}

.toolbar-actions {
  margin-left: auto;
  display: flex;
  gap: var(--space-sm);
}

/* Editor Body */
.editor-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

/* Algorithm Palette */
.algorithm-palette {
  width: var(--palette-width);
  min-width: var(--palette-width);
  border-right: 1px solid var(--border);
  overflow-y: auto;
  padding: var(--space-md);
  background: var(--surface);
}

.palette-title {
  font-weight: 600;
  font-size: var(--font-base);
  margin-bottom: var(--space-md);
  color: var(--text-primary);
}

.palette-group {
  margin-bottom: var(--space-md);
}

.palette-group-title {
  font-size: var(--font-sm);
  color: var(--text-muted);
  padding: var(--space-xs) 0;
  border-bottom: 1px solid var(--border);
  margin-bottom: var(--space-xs);
}

.palette-card {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md);
  margin-bottom: var(--space-xs);
  border-radius: var(--radius-lg);
  cursor: grab;
  transition: background 0.15s;
  border: 1px solid transparent;
}

.palette-card:hover {
  background: var(--hover);
  border-color: var(--border);
}

.palette-card:active {
  cursor: grabbing;
}

.palette-icon {
  font-size: var(--font-2xl);
  flex-shrink: 0;
}

.palette-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.palette-name {
  font-size: var(--font-md);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.palette-types {
  font-size: var(--font-xs);
  color: var(--text-muted);
}

/* Flow Canvas */
.flow-canvas {
  flex: 1;
  overflow: auto;
  padding: var(--space-xl);
}

.canvas-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 300px;
  color: var(--text-muted);
  font-size: var(--font-base);
  border: 2px dashed var(--border);
  border-radius: var(--radius-xl);
  margin: var(--space-xl);
}

.nodes-flow {
  display: flex;
  align-items: center;
  gap: 0;
  min-width: max-content;
  padding: var(--space-xl) 0;
}

.flow-node {
  position: relative;
  display: flex;
  background: var(--surface);
  border: 2px solid var(--border);
  border-radius: var(--radius-xl);
  min-width: var(--node-min-width);
  max-width: var(--node-max-width);
  cursor: pointer;
  transition: all 0.25s;
  overflow: hidden;
  box-shadow: var(--shadow-sm);
}

.flow-node:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.flow-node.selected {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-light);
}

.flow-node.running {
  border-color: var(--color-warning);
  animation: pulse 1s infinite;
}

.flow-node.done {
  border-color: var(--color-success);
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.node-color-bar {
  width: var(--space-xs);
  flex-shrink: 0;
}

.flow-node.data_source .node-color-bar { background: var(--node-color-data-source); }
.flow-node.preprocessing .node-color-bar { background: var(--node-color-preprocessing); }
.flow-node.fill_missing .node-color-bar { background: var(--node-color-fill-missing); }
.flow-node.feature_engineering .node-color-bar { background: var(--node-color-feature-engineering); }
.flow-node.training .node-color-bar { background: var(--node-color-training); }
.flow-node.evaluation .node-color-bar { background: var(--node-color-evaluation); }
.flow-node.output .node-color-bar { background: var(--node-color-output); }

.node-body {
  padding: var(--space-md);
  flex: 1;
}

.node-header {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.node-icon {
  font-size: var(--font-xl);
}

.node-title {
  font-size: var(--font-md);
  font-weight: 600;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-status-dot {
  width: var(--space-sm);
  height: var(--space-sm);
  border-radius: 50%;
  flex-shrink: 0;
}
.node-status-dot.configured { background: var(--el-color-success); }
.node-status-dot.unconfigured { background: var(--el-color-danger); animation: pulse-dot 2s infinite; }
@keyframes pulse-dot { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }

.node-more {
  padding: 0 !important;
  min-width: var(--space-xl);
}

.node-summary {
  margin-top: var(--space-xs);
  font-size: var(--font-xs);
  color: var(--text-muted);
  line-height: 1.4;
}

.node-preview-link {
  margin-top: var(--space-xs);
  font-size: var(--font-xs);
  padding: 0 !important;
}
.node-actions-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
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
  background: #1e1e1e;
  color: #d4d4d4;
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

/* Config panel sections */
.config-preview-section {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
.ds-preview-wrap {
  margin-top: 8px;
}
.preview-hint {
  font-size: 12px;
  color: var(--text-muted);
  text-align: right;
  margin-top: 4px;
}
.ds-column-stats {
  margin-top: 10px;
  border-top: 1px solid var(--border);
  padding-top: 8px;
}
.ds-stats-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  margin-top: 6px;
}
.ds-stat-card {
  padding: 6px 8px;
  border-radius: 4px;
  background: var(--bg-secondary, #f5f7fa);
  font-size: 12px;
}
.ds-stat-card.has-nulls {
  border-left: 3px solid #e6a23c;
}
.ds-stat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.ds-stat-name {
  font-weight: 500;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 55%;
}
.ds-stat-type {
  font-size: 11px;
  color: var(--text-muted);
}
.ds-stat-null {
  margin-top: 2px;
}
.ds-stat-null .text-danger {
  color: #e6a23c;
  font-size: 11px;
}
.ds-stat-range {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.4;
  word-break: break-all;
}
.config-column-strategies {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
.col-strategies-header {
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 8px;
  color: var(--text-secondary);
}
.col-strategy-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.col-strategy-name {
  flex-shrink: 0;
  width: 90px;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.col-strategy-type {
  flex-shrink: 0;
  width: 70px;
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

/* Flow Connector */
.flow-connector {
  display: flex;
  align-items: center;
  gap: 0;
  position: relative;
  padding: var(--space-xs) var(--space-sm);
  min-width: 44px;
  transition: background 0.15s;
  cursor: default;
}

.flow-connector:hover {
  background: var(--primary-light);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.flow-connector.connector-drag-over {
  background: var(--primary-light);
  border-radius: var(--radius-sm);
  padding: var(--space-xs) var(--space-md);
}

.flow-connector.connector-drag-over .connector-drop-hint {
  opacity: 1;
  transform: scale(1);
}

.connector-drop-hint {
  position: absolute;
  width: var(--space-xl);
  height: var(--space-xl);
  border-radius: 50%;
  background: var(--primary);
  color: white;
  font-size: var(--font-base);
  font-weight: bold;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transform: scale(0.5);
  transition: all 0.15s;
  pointer-events: none;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%) scale(0.5);
  z-index: 2;
}

.flow-connector.connector-drag-over .connector-drop-hint {
  transform: translate(-50%, -50%) scale(1);
}

.connector-line {
  width: var(--connector-width);
  height: 2px;
  background: var(--border);
  position: relative;
}

.connector-line::after {
  content: '';
  position: absolute;
  right: -5px;
  top: -4px;
  border: 5px solid transparent;
  border-left: 6px solid var(--border);
}

/* Config Panel */
.config-panel {
  padding: 0 var(--space-sm);
}

.config-footer {
  padding-top: var(--space-lg);
  border-top: 1px solid var(--border);
  margin-top: var(--space-lg);
}

.param-row {
  margin-bottom: var(--space-md);
}

.param-label {
  display: block;
  font-size: var(--font-md);
  color: var(--text-secondary);
  margin-bottom: var(--space-xs);
}

.param-hint {
  color: var(--text-muted);
  font-size: var(--font-xs);
  margin-left: var(--space-xs);
}

/* Column picker */
.column-picker {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-sm);
}

.transform-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.transform-row {
  display: flex;
  gap: var(--space-xs);
  align-items: center;
}

.column-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-xs);
  padding-bottom: var(--space-xs);
  border-bottom: 1px solid var(--border);
}

.column-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 240px;
  overflow-y: auto;
}

.column-list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px var(--space-xs);
  border-radius: var(--radius-sm);
}

.column-list-item:hover { background: var(--fill-light); }
.column-list-item.is-target { background: var(--el-color-warning-light-9); }

.target-badge {
  font-size: var(--font-xs);
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-8);
  padding: 0 var(--space-xs);
  border-radius: var(--radius-sm);
}

.target-hint {
  font-size: var(--font-xs);
  color: var(--text-muted);
  margin-top: var(--space-xs);
}

.col-name { font-size: var(--font-sm); }

.col-type {
  font-size: var(--font-xs);
  color: var(--text-muted);
  margin-left: var(--space-xs);
}

.selected-count {
  font-size: var(--font-xs);
  color: var(--text-muted);
}

.col-hint {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

/* Step Picker */
.step-picker {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.step-option {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: all 0.2s;
}

.step-option:hover {
  border-color: var(--primary);
  background: var(--primary-light);
}

.step-option-icon {
  font-size: var(--font-2xl);
  width: 40px;
  text-align: center;
}

.step-option-info {
  display: flex;
  flex-direction: column;
}

.step-option-title {
  font-weight: 600;
  font-size: var(--font-base);
}

.step-option-desc {
  color: var(--text-muted);
  font-size: var(--font-sm);
}

/* Execution Results */
.run-results {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  margin-top: var(--space-lg);
}

.results-header {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.results-title {
  font-weight: 600;
  font-size: var(--font-base);
}

.results-metrics {
  display: flex;
  gap: var(--space-md);
  flex-wrap: wrap;
  margin-bottom: var(--space-md);
}

.result-metric {
  background: var(--color-success-light);
  border-radius: var(--radius-md);
  padding: var(--space-sm) var(--space-lg);
  text-align: center;
  min-width: 80px;
}

.rm-value {
  display: block;
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-success);
}

.rm-label {
  display: block;
  font-size: var(--font-xs);
  color: var(--text-muted);
  margin-top: 2px;
}

.results-meta {
  font-size: var(--font-sm);
  color: var(--text-muted);
  margin-bottom: var(--space-md);
  display: flex;
  gap: var(--space-xs);
}

.results-features { margin-top: var(--space-sm); }

.rf-title {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  font-weight: 500;
}

.rf-bars {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  margin-top: var(--space-xs);
}

.rf-bar-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.rf-name {
  width: 100px;
  font-size: var(--font-xs);
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rf-track {
  flex: 1;
  height: var(--space-xs);
  background: var(--border-light);
  border-radius: 3px;
  overflow: hidden;
}

.rf-fill {
  height: 100%;
  background: var(--primary);
  border-radius: 3px;
}

.rf-val {
  width: 40px;
  font-size: var(--font-xs);
  color: var(--text-muted);
  text-align: right;
}

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
  color: #e6a23c;
  font-size: 12px;
}
.preview-null-grid .pnr-after {
  font-size: 12px;
}
.preview-null-grid .pnr-after.resolved {
  color: #67c23a;
}
.preview-null-clean {
  font-size: 12px;
  color: #67c23a;
  padding: 4px 0;
}
.preview-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.feat-tag {
  font-size: 12px;
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

.preview-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.feat-tag { font-size: var(--font-xs); }

.feat-more {
  font-size: var(--font-xs);
  color: var(--text-muted);
  line-height: var(--space-xl);
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

.feat-analysis-panel {
  max-height: 400px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-sm);
}

.target-dist {
  margin-bottom: 8px;
  padding: 6px 8px;
  background: var(--surface);
  border-radius: var(--radius);
}

.target-dist-item {
  display: flex;
  justify-content: space-between;
  padding: 2px 0;
  font-size: var(--font-sm);
}

.td-label { font-weight: 500; }
.td-count { color: var(--text-muted); }

.feature-stats-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.feature-stat-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: baseline;
  padding: 4px 8px;
  border-radius: var(--radius);
  font-size: var(--font-xs);
  background: var(--surface);
}

.feature-stat-row.has-missing {
  background: #fef0f0;
  border: 1px solid #fbc4c4;
}

.fs-name { font-weight: 600; min-width: 80px; }
.fs-dtype { color: var(--text-muted); font-size: 10px; }
.fs-null { font-size: 10px; }
.fs-null.text-danger { color: var(--el-color-danger); font-weight: 600; }
.fs-stat { color: var(--text-secondary); flex: 1; min-width: 200px; }
</style>
