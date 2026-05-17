<template>
  <el-drawer
    :model-value="showNodeConfig"
    :title="selectedNodeTitle"
    size="420px"
    direction="rtl"
    :modal="false"
    @update:model-value="emit('update:showNodeConfig', $event)"
  >
    <div v-if="selectedNode" class="config-panel">
      <!-- Data Source Config -->
      <template v-if="selectedNode.type === 'data_source'">
        <el-form label-width="100px" size="small">
          <el-form-item label="节点名称">
            <el-input :model-value="selectedNode.config.title" @update:model-value="updateConfig('title', $event)" />
          </el-form-item>
          <el-form-item label="数据表">
            <el-select
              :model-value="selectedNode.config.table"
              placeholder="选择数据表"
              style="width: 100%"
              :teleported="false"
              :loading="loadingTables"
              filterable
              @update:model-value="onTableSelected"
            >
              <el-option
                v-for="t in tableOptions"
                :key="t.name"
                :label="t.comment ? `${t.name} (${t.comment})` : t.name"
                :value="t.name"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="筛选条件">
            <el-input
              :model-value="selectedNode.config.filter"
              placeholder="如: status = 1"
              @update:model-value="updateConfig('filter', $event)"
            />
          </el-form-item>
        </el-form>
        <div v-if="selectedNode.config.table" class="config-preview-section">
          <el-button size="small" :loading="dsPreviewLoading" @click="emit('loadDsPreview')">
            {{ dsPreviewRows.length ? '刷新预览' : '快速预览数据' }}
          </el-button>
          <div v-if="dsPreviewRows.length" class="ds-preview-wrap">
            <div class="preview-hint">共 {{ dsPreviewTotalRows }} 行 · 前 {{ dsPreviewRows.length }} 行预览</div>
            <el-table :data="dsPreviewRows" size="small" border stripe max-height="220" style="width: 100%">
              <el-table-column
                v-for="col in dsPreviewColumns"
                :key="col"
                :prop="col"
                :label="col"
                min-width="90"
                show-overflow-tooltip
              />
            </el-table>
          </div>
          <div v-if="dsPreviewColumnStats.length" class="ds-column-stats">
            <div class="col-strategies-header">列统计</div>
            <div class="ds-stats-grid">
              <div
                v-for="cs in dsPreviewColumnStats"
                :key="cs.name"
                class="ds-stat-card"
                :class="{ 'has-nulls': (cs.nulls || 0) > 0 }"
              >
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
                  <span v-if="cs.topValues?.length">
                    · Top:
                    <template v-for="(tv, i) in cs.topValues" :key="i">
                      {{ i > 0 ? ', ' : '' }}{{ tv.val }}({{ tv.cnt }})
                    </template>
                  </span>
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
            <el-input :model-value="selectedNode.config.title" @update:model-value="updateConfig('title', $event)" />
          </el-form-item>
          <el-form-item label="缺失值处理">
            <el-select
              :model-value="selectedNode.config.handleMissing"
              style="width: 100%"
              :teleported="false"
              @update:model-value="updateConfig('handleMissing', $event)"
            >
              <el-option label="删除缺失行 (drop)" value="drop" />
              <el-option label="填充均值 (mean)" value="fill_mean" />
              <el-option label="填充中位数 (median)" value="fill_median" />
              <el-option label="不处理" value="none" />
            </el-select>
          </el-form-item>
          <el-form-item label="分类编码">
            <el-select
              :model-value="selectedNode.config.encoding"
              style="width: 100%"
              :teleported="false"
              @update:model-value="updateConfig('encoding', $event)"
            >
              <el-option label="Label Encoding" value="label" />
              <el-option label="One-Hot Encoding" value="onehot" />
              <el-option label="不编码" value="none" />
            </el-select>
          </el-form-item>
          <el-form-item label="特征缩放">
            <el-select
              :model-value="selectedNode.config.scaling"
              style="width: 100%"
              :teleported="false"
              @update:model-value="updateConfig('scaling', $event)"
            >
              <el-option label="StandardScaler (标准化)" value="standard" />
              <el-option label="MinMaxScaler (归一化)" value="minmax" />
              <el-option label="不缩放" value="none" />
            </el-select>
          </el-form-item>
        </el-form>
        <div v-if="columnOptions.length" class="config-column-strategies">
          <div class="col-strategies-header">
            逐列缺失值策略
            <el-button size="small" type="primary" link :loading="trialMissingLoading" @click="emit('runMissingTrial')" style="margin-left: auto">
              批量试运行
            </el-button>
          </div>
          <div v-for="col in columnOptions" :key="col.name" class="col-strategy-row">
            <span class="col-strategy-name" :title="col.name">{{ col.name }}</span>
            <span class="col-strategy-type">{{ col.type }}</span>
            <el-select
              size="small"
              :model-value="emit('getColumnStrategy', col.name)"
              style="flex: 1"
              :teleported="false"
              @update:model-value="v => emit('updateColumnStrategy', col.name, v)"
            >
              <el-option label="继承全局" value="inherit" />
              <el-option label="删除缺失行" value="drop" />
              <el-option label="填充均值" value="fill_mean" />
              <el-option label="填充中位数" value="fill_median" />
              <el-option label="填充众数" value="fill_mode" />
              <el-option label="不处理" value="none" />
            </el-select>
            <span v-if="trialMissingBadge(col.name)" class="trial-badge" :class="trialMissingBadge(col.name).cls">
              {{ trialMissingBadge(col.name).text }}
            </span>
          </div>
        </div>
      </template>

      <!-- Feature Engineering Config -->
      <template v-if="selectedNode.type === 'feature_engineering'">
        <el-form label-width="100px" size="small">
          <el-form-item label="节点名称">
            <el-input :model-value="selectedNode.config.title" @update:model-value="updateConfig('title', $event)" />
          </el-form-item>
          <el-form-item label="目标列">
            <el-select
              :model-value="selectedNode.config.targetColumn"
              placeholder="选择目标列"
              style="width: 100%"
              :teleported="false"
              :disabled="!columnOptions.length"
              @update:model-value="onTargetColumnChange"
            >
              <el-option
                v-for="col in columnOptions"
                :key="col.name"
                :label="`${col.name} (${col.type})`"
                :value="col.name"
              />
            </el-select>
            <div v-if="selectedNode.config.targetColumn" class="target-hint">
              目标列已自动从特征列中排除
            </div>
          </el-form-item>
          <!-- Target Preprocessing -->
          <el-form-item v-if="selectedNode.config.targetColumn" label="目标预处理">
            <div class="target-pp-options">
              <div v-if="isClassificationModel" class="target-pp-row">
                <el-checkbox
                  :model-value="selectedNode.config.targetPreprocessing?.smote || false"
                  @update:model-value="updateTargetPP('smote', $event)"
                >
                  不平衡数据使用SMOTE
                </el-checkbox>
                <span class="target-pp-hint">自动对少数类过采样</span>
              </div>
              <div v-if="isRegressionModel" class="target-pp-row">
                <el-checkbox
                  :model-value="selectedNode.config.targetPreprocessing?.logTransform || false"
                  @update:model-value="updateTargetPP('logTransform', $event)"
                >
                  偏斜目标使用对数变换
                </el-checkbox>
                <span class="target-pp-hint">对右偏分布取log1p</span>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="特征列">
            <div v-if="columnOptions.length" class="column-picker">
              <div class="column-list-header">
                <el-checkbox :model-value="featSelectAll" @update:model-value="v => emit('featSelectAllChange', v)">全选</el-checkbox>
                <span class="selected-count">已选 {{ selectedFeatCount }} / {{ columnOptions.length }} 列</span>
              </div>
              <div class="column-list">
                <div
                  v-for="col in columnOptions"
                  :key="col.name"
                  class="column-list-item"
                  :class="{ 'is-target': col.name === selectedNode.config.targetColumn }"
                >
                  <el-checkbox
                    :model-value="featChecked[col.name]"
                    @update:model-value="v => onFeatCheckedChange(col.name, v)"
                    :disabled="col.name === selectedNode.config.targetColumn"
                  >
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
            <el-button
              size="small"
              type="success"
              :loading="featAnalyzing"
              :disabled="!selectedNode.config.targetColumn || selectedFeatCount === 0"
              @click="emit('analyzeFeatures')"
            >
              {{ featAnalysis ? '刷新分析' : '分析特征' }}
            </el-button>
            <div v-if="featAnalysis" class="feat-analysis-panel" style="margin-top: 8px">
              <div class="preview-stats" style="margin-bottom: 8px">
                <div class="preview-stat">
                  <span class="ps-value">{{ featAnalysis.featureCount }}</span>
                  <span class="ps-label">特征数</span>
                </div>
                <div class="preview-stat">
                  <span class="ps-value">{{ featAnalysis.sampleShape?.[0] }} x {{ featAnalysis.sampleShape?.[1] }}</span>
                  <span class="ps-label">矩阵形状</span>
                </div>
              </div>
              <div v-if="featAnalysis.targetDistribution" class="target-dist">
                <div class="preview-stat"><span class="ps-label">目标列分布</span></div>
                <div v-for="(count, label) in featAnalysis.targetDistribution" :key="label" class="target-dist-item">
                  <span class="td-label">{{ label }}</span>
                  <span class="td-count">{{ count }} 条</span>
                </div>
              </div>
              <div
                v-if="featAnalysis.correlations && Object.keys(featAnalysis.correlations).length"
                class="target-dist"
                style="margin-top: 6px"
              >
                <div class="preview-stat"><span class="ps-label">与目标相关性</span></div>
                <div class="preview-tags" style="margin-top: 4px">
                  <el-tag
                    v-for="col in Object.keys(featAnalysis.correlations)"
                    :key="col"
                    size="small"
                    :type="Math.abs(featAnalysis.correlations[col]) > 0.3 ? 'warning' : 'info'"
                    class="feat-tag"
                  >
                    {{ col }}: {{ featAnalysis.correlations[col] > 0 ? '+' : '' }}{{ featAnalysis.correlations[col] }}
                  </el-tag>
                </div>
              </div>
              <div v-if="featAnalysis.featureStats?.length" class="feature-stats-list">
                <div
                  v-for="fs in featAnalysis.featureStats"
                  :key="fs.name"
                  class="feature-stat-row"
                  :class="{ 'has-missing': fs.nullPct > 0 }"
                >
                  <span class="fs-name">{{ fs.name }}</span>
                  <span class="fs-dtype">{{ fs.dtype }}</span>
                  <span class="fs-null" :class="{ 'text-danger': fs.nullPct > 0 }">{{ fs.nullPct }}% 缺失</span>
                  <span
                    v-if="featAnalysis.correlations?.[fs.name] != null"
                    class="fs-corr"
                    :style="{
                      color: Math.abs(featAnalysis.correlations[fs.name]) > 0.3 ? 'var(--el-color-warning)' : 'var(--text-muted)',
                      fontSize: '11px'
                    }"
                  >
                    r={{ featAnalysis.correlations[fs.name] > 0 ? '+' : '' }}{{ featAnalysis.correlations[fs.name] }}
                  </span>
                  <span v-if="fs.mean != null" class="fs-stat">
                    均值: {{ fs.mean }} · 标准差: {{ fs.std }} · 范围: [{{ fs.min }}, {{ fs.max }}]
                  </span>
                  <span v-else-if="fs.topValues" class="fs-stat">
                    Top:
                    <template v-for="(v, i) in Object.entries(fs.topValues).slice(0, 3)" :key="i">
                      {{ i > 0 ? ', ' : '' }}{{ v[0] }}({{ v[1] }})
                    </template>
                    · {{ fs.unique }} 个唯一值
                  </span>
                </div>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="特征变换">
            <div class="transform-list">
              <div
                v-for="(tf, i) in (selectedNode.config.transforms || [])"
                :key="i"
                class="transform-row"
                style="flex-wrap: wrap; gap: 4px"
              >
                <el-select
                  :model-value="tf.type"
                  style="width: 120px"
                  size="small"
                  :teleported="false"
                  @update:model-value="onTransformTypeChange(tf, $event)"
                >
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
                <el-select
                  :model-value="tf.columns"
                  multiple
                  placeholder="选择列"
                  style="flex: 1; min-width: 120px"
                  size="small"
                  :teleported="false"
                  filterable
                  @update:model-value="updateTransformField(i, 'columns', $event)"
                >
                  <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
                </el-select>
                <!-- Polynomial degree -->
                <el-input-number
                  v-if="tf.type === 'polynomial'"
                  :model-value="tf.degree"
                  :min="2"
                  :max="4"
                  size="small"
                  style="width: 80px"
                  placeholder="阶数"
                  @update:model-value="updateTransformField(i, 'degree', $event)"
                />
                <!-- Binning config -->
                <template v-if="tf.type === 'binning'">
                  <el-select
                    :model-value="tf.strategy"
                    style="width: 100px"
                    size="small"
                    :teleported="false"
                    @update:model-value="updateTransformField(i, 'strategy', $event)"
                  >
                    <el-option label="等宽" value="equal_width" />
                    <el-option label="等频" value="equal_freq" />
                    <el-option label="最优" value="optimal" />
                    <el-option label="自定义" value="custom" />
                  </el-select>
                  <el-input-number
                    :model-value="tf.bins"
                    :min="2"
                    :max="20"
                    size="small"
                    style="width: 70px"
                    placeholder="箱数"
                    @update:model-value="updateTransformField(i, 'bins', $event)"
                  />
                  <el-input
                    v-if="tf.strategy === 'custom'"
                    :model-value="tf.edgesInput"
                    placeholder="边界,如:0,30,60,100"
                    size="small"
                    style="width: 160px"
                    @update:model-value="updateTransformField(i, 'edgesInput', $event)"
                    @change="emit('parseEdges', tf)"
                  />
                </template>
                <!-- Date extract parts -->
                <template v-if="tf.type === 'date_extract'">
                  <el-select
                    :model-value="tf.partsArr"
                    multiple
                    placeholder="提取部分"
                    style="width: 180px"
                    size="small"
                    :teleported="false"
                    @update:model-value="updateTransformField(i, 'partsArr', $event)"
                  >
                    <el-option label="年" value="year" />
                    <el-option label="月" value="month" />
                    <el-option label="日" value="day" />
                    <el-option label="星期" value="weekday" />
                    <el-option label="季度" value="quarter" />
                  </el-select>
                </template>
                <el-button size="small" text type="danger" @click="emit('removeTransform', i)">X</el-button>
              </div>
              <el-button size="small" type="primary" link @click="emit('addTransform')">+ 添加变换</el-button>
            </div>
          </el-form-item>
        </el-form>
      </template>

      <!-- Training Config -->
      <template v-if="selectedNode.type === 'training'">
        <el-form label-width="100px" size="small">
          <el-form-item label="节点名称">
            <el-input :model-value="selectedNode.config.title" @update:model-value="updateConfig('title', $event)" />
          </el-form-item>
          <el-form-item label="模型类型">
            <el-select
              :model-value="selectedNode.config.modelType"
              style="width: 100%"
              :teleported="false"
              @update:model-value="onModelTypeChange"
            >
              <el-option v-for="mt in modelTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="算法">
            <el-select
              :model-value="selectedNode.config.algorithm"
              style="width: 100%"
              :teleported="false"
              @update:model-value="updateConfig('algorithm', $event)"
            >
              <el-option v-for="a in algoOptions" :key="a.value" :label="a.label" :value="a.value" />
            </el-select>
          </el-form-item>
          <el-divider content-position="left">超参数</el-divider>
          <div v-for="p in currentAlgoParams" :key="p.key" class="param-row">
            <label class="param-label">
              {{ p.label }}
              <span v-if="p.hint" class="param-hint">{{ p.hint }}</span>
            </label>
            <el-input-number
              v-if="p.type === 'int'"
              :model-value="selectedNode.config.hyperparams?.[p.key]"
              :min="p.min"
              :max="p.max"
              :step="p.step || 1"
              size="small"
              style="width: 100%"
              @update:model-value="updateHyperparam(p.key, $event)"
            />
            <el-input-number
              v-else-if="p.type === 'float'"
              :model-value="selectedNode.config.hyperparams?.[p.key]"
              :min="p.min"
              :max="p.max"
              :step="p.step || 0.1"
              :precision="2"
              size="small"
              style="width: 100%"
              @update:model-value="updateHyperparam(p.key, $event)"
            />
            <el-select
              v-else-if="p.type === 'select'"
              :model-value="selectedNode.config.hyperparams?.[p.key]"
              size="small"
              style="width: 100%"
              :teleported="false"
              @update:model-value="updateHyperparam(p.key, $event)"
            >
              <el-option v-for="o in p.options" :key="o" :label="o" :value="o" />
            </el-select>
          </div>
        </el-form>
      </template>

      <!-- Evaluation Config -->
      <template v-if="selectedNode.type === 'evaluation'">
        <el-form label-width="100px" size="small">
          <el-form-item label="节点名称">
            <el-input :model-value="selectedNode.config.title" @update:model-value="updateConfig('title', $event)" />
          </el-form-item>
          <el-form-item label="验证模式">
            <el-select
              :model-value="selectedNode.config.validationMode"
              style="width: 100%"
              :teleported="false"
              @update:model-value="onValidationModeChange"
            >
              <el-option label="训练/测试分割" value="train_test" />
              <el-option label="交叉验证" value="cv" />
              <el-option label="样本外验证" value="oos" />
              <el-option label="时间外验证" value="temporal" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="selectedNode.config.validationMode === 'temporal'" label="时间列">
            <el-select
              :model-value="selectedNode.config.temporalColumn"
              placeholder="选择时间列"
              style="width: 100%"
              :teleported="false"
              filterable
              @update:model-value="updateConfig('temporalColumn', $event)"
            >
              <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="测试集比例">
            <el-slider
              :model-value="selectedNode.config.testSize"
              :min="10"
              :max="40"
              :step="5"
              :format-tooltip="v => v + '%'"
              @update:model-value="updateConfig('testSize', $event)"
            />
          </el-form-item>
          <el-form-item label="交叉验证">
            <el-select
              :model-value="selectedNode.config.cvFold"
              style="width: 100%"
              :teleported="false"
              @update:model-value="updateConfig('cvFold', $event)"
            >
              <el-option label="不使用" :value="0" />
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
            <el-input :model-value="selectedNode.config.title" @update:model-value="updateConfig('title', $event)" />
          </el-form-item>
          <el-form-item label="填充策略">
            <el-select
              :model-value="selectedNode.config.strategy"
              style="width: 100%"
              :teleported="false"
              @update:model-value="updateConfig('strategy', $event)"
            >
              <el-option label="自动 (数值列填均值，分类列填众数)" value="auto" />
              <el-option label="均值填充" value="mean" />
              <el-option label="中位数填充" value="median" />
              <el-option label="众数填充" value="mode" />
              <el-option label="固定值" value="constant" />
            </el-select>
          </el-form-item>
          <el-form-item label="指定列">
            <el-select
              :model-value="selectedNode.config.columns"
              multiple
              placeholder="留空=全部列"
              style="width: 100%"
              :teleported="false"
              filterable
              @update:model-value="updateConfig('columns', $event)"
            >
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
            <el-input :model-value="selectedNode.config.title" @update:model-value="updateConfig('title', $event)" />
          </el-form-item>
          <el-form-item label="输出表名">
            <el-input
              :model-value="selectedNode.config.table"
              placeholder="如: prediction_results"
              @update:model-value="updateConfig('table', $event)"
            />
          </el-form-item>
          <el-form-item label="写入模式">
            <el-select
              :model-value="selectedNode.config.mode"
              style="width: 100%"
              :teleported="false"
              @update:model-value="updateConfig('mode', $event)"
            >
              <el-option label="追加 (append)" value="append" />
              <el-option label="替换 (replace)" value="replace" />
            </el-select>
          </el-form-item>
          <el-form-item label="自动建表">
            <el-switch
              :model-value="selectedNode.config.autoCreate"
              active-text="表不存在时自动创建"
              @update:model-value="updateConfig('autoCreate', $event)"
            />
          </el-form-item>
        </el-form>
      </template>

      <div class="config-footer">
        <el-button size="small" type="primary" @click="emit('update:showNodeConfig', false)">完成</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue'

const stepTypes = [
  { type: 'data_source', icon: '📥', title: '数据接入', desc: '从数据库读取数据' },
  { type: 'preprocessing', icon: '🔧', title: '数据预处理', desc: '缺失值处理(含逐列策略)、编码、缩放' },
  { type: 'fill_missing', icon: '🩹', title: '高级缺失值处理', desc: '按列精细配置缺失值填充' },
  { type: 'feature_engineering', icon: '⚙️', title: '特征工程', desc: '特征选择和目标定义' },
  { type: 'training', icon: '🧠', title: '模型训练', desc: '选择算法并训练模型' },
  { type: 'evaluation', icon: '📊', title: '模型评估', desc: '评估指标和验证策略' },
  { type: 'output', icon: '💾', title: '输出写入', desc: '将预测结果写入数据库表' }
]

const props = defineProps({
  selectedNode: { type: Object, default: null },
  showNodeConfig: { type: Boolean, default: false },
  selectedNodeTitle: { type: String, default: '' },
  editingPipeline: { type: Object, default: null },
  columnOptions: { type: Array, default: () => [] },
  tableOptions: { type: Array, default: () => [] },
  loadingTables: { type: Boolean, default: false },
  modelTypes: { type: Array, default: () => [] },
  algorithms: { type: Array, default: () => [] },
  featChecked: { type: Object, default: () => ({}) },
  featSelectAll: { type: Boolean, default: false },
  selectedFeatCount: { type: Number, default: 0 },
  featAnalysis: { type: Object, default: null },
  featAnalyzing: { type: Boolean, default: false },
  dsPreviewLoading: { type: Boolean, default: false },
  dsPreviewRows: { type: Array, default: () => [] },
  dsPreviewColumns: { type: Array, default: () => [] },
  dsPreviewTotalRows: { type: Number, default: 0 },
  dsPreviewColumnStats: { type: Array, default: () => [] },
  scriptLoading: { type: Boolean, default: false },
  trialMissingLoading: { type: Boolean, default: false },
  trialMissingResult: { type: Object, default: null }
})

const emit = defineEmits([
  'update:showNodeConfig',
  'update:selectedNode',
  'update:featChecked',
  'tableSelected',
  'targetColumnChange',
  'featSelectAllChange',
  'analyzeFeatures',
  'loadDsPreview',
  'modelTypeChange',
  'addTransform',
  'removeTransform',
  'onTransformTypeChange',
  'parseEdges',
  'getColumnStrategy',
  'updateColumnStrategy',
  'syncFeatCols',
  'runMissingTrial'
])

const algoOptions = computed(() => {
  const mt = props.selectedNode?.config?.modelType
  if (!mt || !props.algorithms.length) return []
  return props.algorithms
    .filter(a => !a.modelTypes || a.modelTypes.includes(mt))
    .map(a => ({ value: a.algorithmId, label: (a.icon ? a.icon + ' ' : '') + a.name }))
})

const currentAlgoParams = computed(() => {
  const algoId = props.selectedNode?.config?.algorithm
  if (!algoId) return []
  const algo = props.algorithms.find(a => a.algorithmId === algoId)
  if (!algo?.params) return []
  return Object.entries(algo.params).map(([key, p]) => ({ key, ...p }))
})

function updateConfig(field, value) {
  if (!props.selectedNode) return
  const updated = {
    ...props.selectedNode,
    config: { ...props.selectedNode.config, [field]: value }
  }
  emit('update:selectedNode', updated)
}

function updateHyperparam(key, value) {
  if (!props.selectedNode) return
  const updated = {
    ...props.selectedNode,
    config: {
      ...props.selectedNode.config,
      hyperparams: { ...(props.selectedNode.config.hyperparams || {}), [key]: value }
    }
  }
  emit('update:selectedNode', updated)
}

function onTableSelected(tableName) {
  updateConfig('table', tableName)
  emit('tableSelected', tableName)
}

function onTargetColumnChange(target) {
  updateConfig('targetColumn', target)
  emit('targetColumnChange', target)
}

function onModelTypeChange(val) {
  updateConfig('modelType', val)
  emit('modelTypeChange', val)
}

function onValidationModeChange(val) {
  const updates = { validationMode: val }
  if (val !== 'temporal') {
    updates.temporalColumn = null
  }
  if (!props.selectedNode) return
  const updated = {
    ...props.selectedNode,
    config: { ...props.selectedNode.config, ...updates }
  }
  emit('update:selectedNode', updated)
}

function onTransformTypeChange(tf, newType) {
  emit('onTransformTypeChange', { ...tf, type: newType })
}

function updateTransformField(index, field, value) {
  if (!props.selectedNode) return
  const transforms = [...(props.selectedNode.config.transforms || [])]
  transforms[index] = { ...transforms[index], [field]: value }
  const updated = {
    ...props.selectedNode,
    config: { ...props.selectedNode.config, transforms }
  }
  emit('update:selectedNode', updated)
}

function onFeatCheckedChange(colName, checked) {
  const updated = { ...props.featChecked, [colName]: checked }
  emit('update:featChecked', updated)
  emit('syncFeatCols')
}

const isClassificationModel = computed(() => {
  try {
    const nodes = typeof props.editingPipeline?.nodes === 'string'
      ? JSON.parse(props.editingPipeline.nodes)
      : props.editingPipeline?.nodes || []
    const trainingNode = nodes.find(n => n.type === 'training')
    const mt = trainingNode?.config?.modelType
    return mt === 'classification' || mt === 'anomaly_detection'
  } catch { return false }
})

const isRegressionModel = computed(() => {
  try {
    const nodes = typeof props.editingPipeline?.nodes === 'string'
      ? JSON.parse(props.editingPipeline.nodes)
      : props.editingPipeline?.nodes || []
    const trainingNode = nodes.find(n => n.type === 'training')
    const mt = trainingNode?.config?.modelType
    return mt === 'regression'
  } catch { return false }
})

function updateTargetPP(key, value) {
  if (!props.selectedNode) return
  const current = props.selectedNode.config.targetPreprocessing || {}
  const updated = {
    ...props.selectedNode,
    config: {
      ...props.selectedNode.config,
      targetPreprocessing: { ...current, [key]: value }
    }
  }
  emit('update:selectedNode', updated)
}

function trialMissingBadge(colName) {
  const result = props.trialMissingResult
  if (!result || result.status === 'error') return null
  const beforeNulls = result.beforeNulls || {}
  const remainingNulls = result.remainingNulls || {}
  const before = beforeNulls[colName]
  if (before == null || before === 0) return null
  const after = remainingNulls[colName] || 0
  return {
    text: after === 0 ? `${before} -> 0` : `${before} -> ${after}`,
    cls: after === 0 ? 'resolved' : 'partial'
  }
}
</script>

<style scoped>
/* Config Panel */
.config-panel {
  padding: 0 var(--space-sm);
}

.config-footer {
  padding-top: var(--space-lg);
  border-top: 1px solid var(--border);
  margin-top: var(--space-lg);
}

/* Config preview section */
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

/* Column stats */
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
  border-left: 3px solid var(--el-color-warning);
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
  color: var(--el-color-warning);
  font-size: 11px;
}

.ds-stat-range {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.4;
  word-break: break-all;
}

/* Column strategies */
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

.trial-badge {
  flex-shrink: 0;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
  white-space: nowrap;
}

.trial-badge.resolved {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

.trial-badge.partial {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}

/* Column picker */
.column-picker {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-sm);
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

.column-list-item:hover {
  background: var(--fill-light);
}

.column-list-item.is-target {
  background: var(--el-color-warning-light-9);
}

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

.col-name {
  font-size: var(--font-sm);
}

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

/* Transform list */
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

/* Param row */
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

/* Target preprocessing */
.target-pp-options {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.target-pp-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.target-pp-hint {
  font-size: 11px;
  color: var(--text-muted);
  padding-left: 24px;
}

/* Feature analysis panel */
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

.td-label {
  font-weight: 500;
}

.td-count {
  color: var(--text-muted);
}

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
  background: var(--color-danger-light);
  border: 1px solid var(--danger-border);
}

.fs-name {
  font-weight: 600;
  min-width: 80px;
}

.fs-dtype {
  color: var(--text-muted);
  font-size: 10px;
}

.fs-null {
  font-size: 10px;
}

.fs-null.text-danger {
  color: var(--el-color-danger);
  font-weight: 600;
}

.fs-corr {
  font-size: 11px;
}

.fs-stat {
  color: var(--text-secondary);
  flex: 1;
  min-width: 200px;
}
</style>
