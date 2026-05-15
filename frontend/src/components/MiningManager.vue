<template>
  <div class="mining-manager">
    <!-- Header -->
    <div class="mining-header">
      <div class="header-left">
        <button class="back-btn" @click="$emit('close')">
          <span class="back-arrow">←</span> 返回问数
        </button>
        <h2 class="page-title">数据挖掘管理</h2>
      </div>
      <div class="header-actions">
        <el-select v-model="filterDsId" placeholder="数据源" size="small" clearable style="width: 160px">
          <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
        </el-select>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="mining-tabs">
      <el-tab-pane label="模型管理" name="models">
        <div class="tab-toolbar">
          <el-input v-model="modelSearch" placeholder="搜索模型名称、算法、表名..." size="small" clearable style="width:260px" prefix-icon="Search" />
          <el-button type="primary" size="small" @click="showCreateDialog = true">新建模型</el-button>
        </div>
        <!-- Model List -->
        <div class="mining-body">
      <div v-if="loading" class="mining-loading"><span class="spinner"></span> 加载中...</div>
      <div v-else-if="!models.length" class="mining-empty">
        <div class="empty-icon">🔬</div>
        <p>暂无挖掘模型</p>
        <p class="empty-hint">点击「新建模型」创建第一个数据挖掘流程</p>
      </div>
      <div v-else-if="!filteredModels.length" class="mining-empty">
        <p>没有匹配「{{ modelSearch }}」的模型</p>
      </div>
      <div v-else class="model-grid">
        <div v-for="model in filteredModels" :key="model.id" class="model-card" @click="selectModel(model)">
          <div class="model-card-header">
            <div class="model-card-title">
              <span class="model-type-icon">{{ modelTypeIcon(model.modelType) }}</span>
              <span class="model-name">{{ model.name }}</span>
            </div>
            <span :class="['status-badge', 'status-' + model.status]">{{ statusLabel(model.status) }}</span>
            <span v-if="model.status === 'published' && model.scheduleEnabled" class="schedule-badge" :title="scheduleTooltip(model)">⏰ {{ scheduleIntervalLabel(model) }}</span>
          </div>
          <div class="model-card-body">
            <div class="model-meta">
              <span class="meta-item">{{ algorithmLabel(model.algorithm) }}</span>
              <span class="meta-item">{{ modelTypeLabel(model.modelType) }}</span>
            </div>
            <div class="model-meta">
              <span class="meta-item secondary">表: {{ model.sourceTable || '-' }}</span>
              <span class="meta-item secondary">v{{ model.version }}</span>
              <el-tag v-if="model.conversationId" size="small" effect="plain" type="info">对话构建</el-tag>
              <el-tag v-else-if="model.pipelineId" size="small" effect="plain" type="success">流程编排</el-tag>
              <span v-if="model.pipelineId" class="meta-item secondary" style="cursor:pointer;color:var(--el-color-primary)" @click="goToPipeline(model.pipelineId)">流程 #{{ model.pipelineId }}</span>
            </div>
            <div v-if="model.description" class="model-desc">{{ model.description }}</div>
            <div v-if="model.metrics" class="model-metrics">
              <span :class="['metric-primary', 'quality-' + metricQuality(primaryMetricRaw(model), model.modelType)]">
                {{ primaryMetricLabel(model) }}
                <strong>{{ primaryMetricValue(model) }}</strong>
              </span>
              <template v-for="(val, key) in parsedMetrics(model.metrics)" :key="key">
                <span v-if="!isPrimary(key, model.modelType)" :class="['metric-chip', 'quality-' + metricQuality(val, model.modelType, key)]">{{ formatMetricName(key) }} {{ formatMetricValue(key, val) }}</span>
              </template>
            </div>
          </div>
          <div class="model-card-actions">
            <el-button size="small" :loading="trainingId === model.id" @click.stop="handleTrain(model.id)">
              {{ model.status === 'training' ? '训练中...' : '训练' }}
            </el-button>
            <el-button v-if="model.status !== 'draft' && model.status !== 'training'" size="small"
              @click.stop="editModel(model)">调参</el-button>
            <el-button v-if="model.status === 'trained' || model.status === 'offline'" size="small" type="success"
              @click.stop="handlePublish(model.id)">发布</el-button>
            <el-button v-if="model.status === 'published'" size="small" type="warning"
              @click.stop="handleOffline(model.id)">下线</el-button>
            <el-button v-if="model.status === 'published' || model.status === 'trained'" size="small" type="primary"
              @click.stop="openPredict(model)">预测</el-button>
            <el-button v-if="model.status === 'published'" size="small" plain
              @click.stop="openBatchPredict(model)">批量预测</el-button>
            <el-dropdown trigger="click" @command="cmd => onActionCmd(cmd, model)" @click.stop>
              <el-button size="small" @click.stop>更多 ▾</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="model.status === 'draft'" command="validate">训练前校验</el-dropdown-item>
                  <el-dropdown-item v-if="model.status === 'published'" command="batchPredict">批量预测</el-dropdown-item>
                  <el-dropdown-item command="schedule">{{ model.scheduleEnabled ? '调度设置 (已启用)' : '调度设置' }}</el-dropdown-item>
                  <el-dropdown-item v-if="model.status === 'published' || model.status === 'trained'" command="predictResults">预测记录</el-dropdown-item>
                  <el-dropdown-item v-if="model.pipelineId" command="viewPipeline">查看流程</el-dropdown-item>
                  <el-dropdown-item command="delete" divided style="color: var(--el-color-danger)">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
      </div>
    </div>
      </el-tab-pane>
      <el-tab-pane label="流程编排" name="pipeline">
        <PipelineEditor ref="pipelineEditorRef" :dataSources="dataSources" />
      </el-tab-pane>
    </el-tabs>

    <!-- Create/Edit Dialog -->
    <el-dialog v-model="showCreateDialog" :title="editingModel ? '编辑模型' : '新建模型'" width="660px" destroy-on-close>
      <el-form :model="form" label-width="100px" size="default">
        <el-form-item label="模型名称" required>
          <el-input v-model="form.name" placeholder="如: 员工离职预测" />
        </el-form-item>
        <el-form-item label="数据源" required>
          <el-select v-model="form.dataSourceId" placeholder="选择数据源" style="width: 100%"
            :teleported="false" @change="onDataSourceChange">
            <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="源数据表" required>
          <el-select v-model="form.sourceTable" placeholder="选择数据表" style="width: 100%"
            :teleported="false" :loading="loadingTables" :disabled="!form.dataSourceId"
            @change="onTableChange" filterable>
            <el-option v-for="t in tableOptions" :key="t.name" :label="t.comment ? `${t.name} (${t.comment})` : t.name" :value="t.name">
              <span>{{ t.name }}</span>
              <span v-if="t.comment" style="color: var(--text-muted); margin-left: 8px; font-size: 12px">{{ t.comment }}</span>
              <span style="float: right; color: var(--text-muted); font-size: 12px">{{ t.rows }}行</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="模型类型" required>
          <el-select v-model="form.modelType" placeholder="选择类型" style="width: 100%" :teleported="false">
            <el-option v-for="mt in modelTypes" :key="mt.id" :label="`${mt.name}`" :value="mt.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="算法" required>
          <el-select v-model="form.algorithm" placeholder="选择算法" style="width: 100%" :teleported="false">
            <el-option v-for="a in filteredAlgorithms" :key="a.algorithmId" :label="`${a.icon || ''} ${a.name}`" :value="a.algorithmId" />
          </el-select>
        </el-form-item>
        <el-form-item label="特征列" required>
          <div v-if="!columnOptions.length" class="col-hint">
            请先选择数据源和数据表
          </div>
          <div v-else class="column-picker">
            <el-checkbox v-model="selectAllFeatures" :indeterminate="featureIndeterminate"
              @change="onSelectAllFeatures" style="margin-bottom: 6px">全选</el-checkbox>
            <div class="column-grid">
              <el-checkbox v-for="col in columnOptions" :key="col.name"
                v-model="featureChecked[col.name]" @change="syncFeatureColumns">
                <span class="col-name">{{ col.name }}</span>
                <span class="col-type">{{ col.type }}</span>
              </el-checkbox>
            </div>
            <div class="selected-count">已选 {{ form.featureColumnsList.length }} / {{ columnOptions.length }} 列</div>
          </div>
        </el-form-item>
        <el-form-item v-if="form.modelType !== 'clustering'" label="目标列">
          <el-select v-model="form.targetColumn" placeholder="选择目标列" style="width: 100%"
            :teleported="false" :disabled="!columnOptions.length" clearable filterable>
            <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name">
              <span>{{ col.name }}</span>
              <span style="color: var(--text-muted); margin-left: 8px; font-size: 12px">{{ col.type }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="缺失值处理">
          <el-select v-model="form.preprocessing.handleMissing" style="width: 100%" :teleported="false">
            <el-option label="删除缺失行" value="drop" />
            <el-option label="填充均值" value="fill_mean" />
            <el-option label="填充中位数" value="fill_median" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类编码">
          <el-select v-model="form.preprocessing.encoding" style="width: 100%" :teleported="false">
            <el-option label="Label Encoding" value="label" />
            <el-option label="One-Hot Encoding" value="onehot" />
          </el-select>
        </el-form-item>
        <el-form-item label="特征缩放">
          <el-select v-model="form.preprocessing.scaling" style="width: 100%" :teleported="false">
            <el-option label="不缩放" value="none" />
            <el-option label="标准化 (StandardScaler)" value="standard" />
            <el-option label="归一化 (MinMaxScaler)" value="minmax" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="模型用途描述" />
        </el-form-item>
        <el-form-item label="验证模式">
          <el-select v-model="form.validationMode" style="width: 100%" :teleported="false">
            <el-option label="训练/测试分割 (默认)" value="train_test" />
            <el-option label="交叉验证 (K-Fold)" value="cv" />
            <el-option label="样本外验证 (OOS)" value="oos" />
            <el-option label="时间外验证 (Temporal)" value="temporal" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.validationMode === 'cv' || form.validationMode === 'oos'" label="CV折数">
          <el-input-number v-model="form.cvFolds" :min="2" :max="20" :step="1" />
        </el-form-item>
        <el-form-item v-if="form.validationMode === 'temporal'" label="时间列">
          <el-select v-model="form.temporalColumn" placeholder="选择时间列" style="width: 100%" :teleported="false" filterable>
            <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          {{ editingModel ? '保存' : '创建' }}
        </el-button>
        <el-button v-if="!editingModel" type="success" :loading="saving" @click="handleSaveAndTrain">
          创建并训练
        </el-button>
      </template>
    </el-dialog>

    <!-- Hyperparameter Edit Dialog -->
    <el-dialog v-model="showParamsDialog" title="调整超参数" width="520px" destroy-on-close>
      <div v-if="paramModel" class="params-editor">
        <p class="params-model-name">{{ paramModel.name }} - {{ algorithmLabel(paramModel.algorithm) }}</p>
        <div v-for="p in algorithmParams(paramModel.algorithm)" :key="p.key" class="param-row">
          <label class="param-label">
            {{ p.label }}
            <span v-if="p.hint" class="param-hint">{{ p.hint }}</span>
          </label>
          <el-input-number v-if="p.type === 'int'" v-model="paramForm[p.key]"
            :min="p.min" :max="p.max" :step="p.step || 1" size="small" style="width: 180px" />
          <el-input-number v-else-if="p.type === 'float'" v-model="paramForm[p.key]"
            :min="p.min" :max="p.max" :step="p.step || 0.1" :precision="2" size="small" style="width: 180px" />
          <el-select v-else-if="p.type === 'select'" v-model="paramForm[p.key]"
            size="small" style="width: 180px" :teleported="false">
            <el-option v-for="o in p.options" :key="o" :label="o" :value="o" />
          </el-select>
          <el-input v-else v-model="paramForm[p.key]" size="small" style="width: 180px" />
        </div>
        <el-button size="small" type="primary" link @click="showAddParamDialog = true" style="margin-top: 8px">+ 添加自定义参数</el-button>
      </div>
      <template #footer>
        <el-button @click="showParamsDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingParams" @click="handleSaveParams">保存参数</el-button>
      </template>
    </el-dialog>

    <!-- Add custom param dialog -->
    <el-dialog v-model="showAddParamDialog" title="添加自定义参数" width="360px" append-to-body>
      <el-form label-width="80px" size="small">
        <el-form-item label="参数名">
          <el-input v-model="newParamKey" placeholder="如: min_samples_split" />
        </el-form-item>
        <el-form-item label="参数值">
          <el-input v-model="newParamValue" placeholder="如: 5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showAddParamDialog = false">取消</el-button>
        <el-button size="small" type="primary" @click="confirmAddParam">确定</el-button>
      </template>
    </el-dialog>

    <!-- Schedule Dialog -->
    <el-dialog v-model="showScheduleDialog" title="定时调度" width="460px" destroy-on-close>
      <div v-if="scheduleModel">
        <p style="margin-bottom: 12px; color: var(--text-secondary)">为「{{ scheduleModel.name }}」配置定时调度</p>
        <el-form label-width="100px" size="default">
          <el-form-item label="启用调度">
            <el-switch v-model="scheduleEnabled" active-text="开" inactive-text="关" />
          </el-form-item>
          <el-form-item label="调度模式">
            <el-radio-group v-model="scheduleMode">
              <el-radio value="train">定期重训</el-radio>
              <el-radio value="predict">定期预测</el-radio>
            </el-radio-group>
            <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">
              {{ scheduleMode === 'predict' ? '用已发布模型对新数据表批量预测，结果写入结果表' : '用最新数据重新训练模型' }}
            </div>
          </el-form-item>
          <el-form-item v-if="scheduleMode === 'predict'" label="输入表">
            <el-select v-model="scheduleInputTable" placeholder="选择预测输入表" style="width: 100%" :teleported="false" filterable>
              <el-option v-for="t in tableOptions" :key="t.name" :label="t.name" :value="t.name" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="scheduleMode === 'predict'" label="结果表">
            <el-input v-model="scheduleResultTable" placeholder="预测结果保存表名" />
          </el-form-item>
          <el-form-item label="调度间隔">
            <el-select v-model="scheduleCron" style="width: 100%" :teleported="false">
              <el-option label="每 30 分钟" value="*/30" />
              <el-option label="每 1 小时" value="*/60" />
              <el-option label="每 6 小时" value="*/360" />
              <el-option label="每 12 小时" value="*/720" />
              <el-option label="每 24 小时" value="*/1440" />
              <el-option label="每周" value="*/10080" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="scheduleMode === 'predict'" label="筛选条件">
            <el-input v-model="scheduleInputFilter" placeholder="如: etl_date = '${etl_date}' 或 status = 'active'" />
            <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">
              支持变量: ${etl_date}、${today}、${yesterday}、${today-N}(N天前)
            </div>
          </el-form-item>
        </el-form>
        <div v-if="scheduleModel.lastRunAt" style="margin-top: 8px; font-size: 12px; color: var(--text-muted)">
          上次运行: {{ new Date(scheduleModel.lastRunAt).toLocaleString('zh-CN') }}
        </div>
      </div>
      <template #footer>
        <el-button @click="showScheduleDialog = false">取消</el-button>
        <el-button type="primary" @click="saveSchedule">保存</el-button>
      </template>
    </el-dialog>

    <!-- Publish Dialog -->
    <el-dialog v-model="showPublishDialog" title="发布模型" width="560px" destroy-on-close>
      <div v-if="publishModel_ref">
        <p style="margin-bottom: 16px; color: var(--text-secondary)">
          发布「{{ publishModel_ref.name }}」后可进行批量预测和定时调度
        </p>
        <el-form label-width="100px" size="default">
          <el-form-item label="预测输入表">
            <el-select v-model="publishConfig.predictInputTable" placeholder="选择预测输入表（可选）" style="width: 100%" :teleported="false" filterable clearable>
              <el-option v-for="t in tableOptions" :key="t.name" :label="t.name" :value="t.name">
                <span>{{ t.name }}</span>
                <span style="float: right; color: var(--text-muted); font-size: 12px">{{ t.rows }}行</span>
              </el-option>
            </el-select>
            <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">发布后批量预测的默认输入表</div>
          </el-form-item>
          <el-form-item label="输入筛选条件">
            <el-input v-model="publishConfig.predictInputFilter" placeholder="如: etl_date = '${etl_date}' 或 status = 'active'" />
            <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">
              支持变量: ${etl_date}、${today}、${yesterday}、${today-N}
            </div>
          </el-form-item>
          <el-form-item label="预测结果表">
            <el-input v-model="publishConfig.predictResultTable" placeholder="如: prediction_results（表不存在时自动创建）" />
            <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">留空则每次预测时指定</div>
          </el-form-item>
          <el-divider />
          <el-form-item label="启用定时调度">
            <el-switch v-model="publishConfig.scheduleEnabled" active-text="开" inactive-text="关" />
          </el-form-item>
          <el-form-item v-if="publishConfig.scheduleEnabled" label="调度模式">
            <el-radio-group v-model="publishConfig.scheduleMode">
              <el-radio value="train">定期重训</el-radio>
              <el-radio value="predict">定期预测</el-radio>
            </el-radio-group>
            <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">
              {{ publishConfig.scheduleMode === 'predict' ? '用已发布模型对新数据批量预测，结果写入结果表' : '用最新数据重新训练模型' }}
            </div>
          </el-form-item>
          <el-form-item v-if="publishConfig.scheduleEnabled" label="调度间隔">
            <el-select v-model="publishConfig.scheduleCron" style="width: 100%" :teleported="false">
              <el-option label="每 30 分钟" value="*/30" />
              <el-option label="每 1 小时" value="*/60" />
              <el-option label="每 6 小时" value="*/360" />
              <el-option label="每 12 小时" value="*/720" />
              <el-option label="每 24 小时" value="*/1440" />
              <el-option label="每周" value="*/10080" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="showPublishDialog = false">取消</el-button>
        <el-button type="primary" :loading="publishLoading" @click="confirmPublish">确认发布</el-button>
      </template>
    </el-dialog>

    <!-- Prediction Dialog -->
    <el-dialog v-model="showPredictDialog" title="模型预测" width="660px" destroy-on-close>
      <div v-if="predictModel_ref">
        <p style="color: var(--text-secondary); margin-bottom: 12px">
          使用「{{ predictModel_ref.name }}」进行预测，输入数据格式为 JSON 数组
        </p>
        <el-form label-width="100px" size="default">
          <el-form-item label="输入数据">
            <el-input v-model="predictInput" type="textarea" :rows="6" placeholder='[{"dept_id": 1, "salary": 15000}]' />
          </el-form-item>
          <el-form-item label="保存到表">
            <el-input v-model="predictSaveTable" placeholder="留空不保存，填写表名则自动建表并写入预测结果" />
          </el-form-item>
        </el-form>
        <div v-if="predictResult" class="predict-result">
          <h4>预测结果</h4>
          <div v-if="predictResult.saved_to" style="margin-bottom: 8px; color: var(--color-success)">
            已保存 {{ predictResult.saved_rows }} 条到表 {{ predictResult.saved_to }}
          </div>
          <el-table :data="predictRows" size="small" stripe border max-height="300">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column v-for="(_, key) in (predictRows[0] || {})" :key="key" :prop="key" :label="key" />
          </el-table>
        </div>
      </div>
      <template #footer>
        <el-button @click="showPredictDialog = false">关闭</el-button>
        <el-button type="primary" :loading="predictLoading" @click="handlePredict">执行预测</el-button>
      </template>
    </el-dialog>
    <!-- Batch Predict Dialog -->
    <el-dialog v-model="showBatchPredictDialog" title="批量预测" width="560px" destroy-on-close>
      <div v-if="batchPredictModel">
        <p style="color: var(--text-secondary); margin-bottom: 12px">
          使用已发布的「{{ batchPredictModel.name }}」对整张表的数据进行批量预测
        </p>
        <el-form label-width="100px" size="default">
          <el-form-item label="模型信息">
            <span>{{ algorithmLabel(batchPredictModel.algorithm) }} · v{{ batchPredictModel.version }}</span>
          </el-form-item>
          <el-form-item label="特征列">
            <div style="display: flex; flex-wrap: wrap; gap: 4px">
              <el-tag v-for="col in parseJson(batchPredictModel.featureColumns, [])" :key="col" size="small">{{ col }}</el-tag>
            </div>
          </el-form-item>
          <el-form-item label="输入表" required>
            <el-select v-model="batchInputTable" placeholder="选择要预测的数据表" style="width: 100%" :teleported="false" filterable>
              <el-option v-for="t in tableOptions" :key="t.name" :label="t.name" :value="t.name">
                <span>{{ t.name }}</span>
                <span style="float: right; color: var(--text-muted); font-size: 12px">{{ t.rows }}行</span>
              </el-option>
            </el-select>
            <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">该表必须包含模型训练时的特征列</div>
          </el-form-item>
          <el-form-item label="结果表">
            <el-input v-model="batchResultTable" placeholder="留空则自动生成表名" />
            <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">
              结果表包含原始数据 + prediction列 + prediction_proba列
            </div>
          </el-form-item>
        </el-form>
        <div v-if="batchPredictResult" style="margin-top: 16px">
          <el-alert type="success" :closable="false" style="margin-bottom: 12px">
            <template #title>
              批量预测完成！共 {{ batchPredictResult.saved_rows }} 条结果已写入表
              <strong>{{ batchPredictResult.saved_to }}</strong>
            </template>
          </el-alert>
          <div style="font-size: 13px; color: var(--text-secondary)">
            结果表列: {{ (batchPredictResult.columns || []).join(', ') }}
          </div>
          <el-button size="small" type="primary" plain style="margin-top: 8px"
            @click="previewResult(batchPredictResult.saved_to)">
            预览结果数据
          </el-button>
          <div v-if="resultPreview.length" style="margin-top: 8px">
            <el-table :data="resultPreview" size="small" stripe border max-height="200">
              <el-table-column type="index" label="#" width="40" />
              <el-table-column v-for="col in resultPreviewColumns" :key="col" :prop="col" :label="col" show-overflow-tooltip />
            </el-table>
            <div style="font-size: 11px; color: var(--text-muted); margin-top: 4px">仅展示前10行</div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="showBatchPredictDialog = false">关闭</el-button>
        <el-button type="primary" :loading="batchPredictLoading" @click="handleBatchPredict">执行批量预测</el-button>
      </template>
    </el-dialog>

    <!-- Prediction Results Dialog -->
    <el-dialog v-model="showPredictResultsDialog" title="预测记录" width="700px" destroy-on-close>
      <div v-if="predictResultsModel">
        <el-table :data="predictionResults" size="small" stripe border max-height="400" v-loading="loadingPredictions">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="predictedAt" label="时间" width="160">
            <template #default="{ row }">{{ formatDate(row.predictedAt) }}</template>
          </el-table-column>
          <el-table-column prop="prediction" label="预测值" width="120" />
          <el-table-column prop="probability" label="置信度" width="80">
            <template #default="{ row }">{{ row.probability ? (row.probability * 100).toFixed(1) + '%' : '-' }}</template>
          </el-table-column>
          <el-table-column prop="inputData" label="输入数据">
            <template #default="{ row }">
              <span style="font-size: 12px">{{ truncateStr(row.inputData, 80) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="resultTable" label="结果表" width="140">
            <template #default="{ row }">
              <el-tag v-if="row.resultTable" size="small">{{ row.resultTable }}</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <el-drawer v-model="showDetail" :title="detailModel?.name || '模型详情'" size="480px" direction="rtl">
      <template v-if="detailModel">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="状态">
            <span :class="['status-badge', 'status-' + detailModel.status]">{{ statusLabel(detailModel.status) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="算法">{{ algorithmLabel(detailModel.algorithm) }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ modelTypeLabel(detailModel.modelType) }}</el-descriptions-item>
          <el-descriptions-item label="源表">{{ detailModel.sourceTable }}</el-descriptions-item>
          <el-descriptions-item label="目标列">{{ detailModel.targetColumn || '-' }}</el-descriptions-item>
          <el-descriptions-item label="版本">v{{ detailModel.version }}</el-descriptions-item>
          <el-descriptions-item v-if="detailModel.pipelineId" label="来源">
            <el-button size="small" link type="primary" @click="goToPipeline(detailModel.pipelineId)">
              关联流程 #{{ detailModel.pipelineId }}
            </el-button>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDate(detailModel.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <!-- Pipeline Node Visualization -->
        <div v-if="detailPipelineNodes.length" class="detail-section">
          <h4 style="display: flex; align-items: center; gap: 8px">
            流程节点
            <el-button v-if="detailModel.pipelineId" size="small" link type="primary" @click="goToPipeline(detailModel.pipelineId)">编辑流程</el-button>
          </h4>
          <div class="pipeline-mini-flow">
            <template v-for="(node, i) in detailPipelineNodes" :key="i">
              <div class="mini-flow-node">
                <span class="mini-flow-icon">{{ pipelineNodeIcon(node.type) }}</span>
                <span class="mini-flow-title">{{ pipelineNodeTitle(node) }}</span>
                <span class="mini-flow-detail">{{ pipelineNodeSummary(node) }}</span>
              </div>
              <span v-if="i < detailPipelineNodes.length - 1" class="mini-flow-arrow">→</span>
            </template>
          </div>
        </div>

        <div v-if="detailModel.featureColumns" class="detail-section">
          <h4>特征列</h4>
          <div class="feature-tags">
            <el-tag v-for="col in parseJson(detailModel.featureColumns, [])" :key="col" size="small" style="margin: 2px">{{ col }}</el-tag>
          </div>
        </div>

        <div v-if="detailModel.metrics" class="detail-section">
          <h4>评估指标</h4>
          <div class="metrics-grid">
            <div v-for="(val, key) in parsedMetrics(detailModel.metrics)" :key="key" class="metric-card">
              <span class="metric-value">{{ formatMetricValue(key, val) }}</span>
              <span class="metric-name">{{ formatMetricName(key) }}</span>
            </div>
          </div>
        </div>

        <div v-if="detailModel.featureImportance" class="detail-section">
          <h4>特征重要性</h4>
          <div class="importance-list">
            <div v-for="(val, key) in sortedImportance" :key="key" class="importance-bar">
              <span class="imp-label">{{ key }}</span>
              <div class="imp-track">
                <div class="imp-fill" :style="{ width: (val / maxImportance * 100) + '%' }"></div>
              </div>
              <span class="imp-value">{{ (val * 100).toFixed(1) }}%</span>
            </div>
          </div>
        </div>

        <div v-if="detailModel.validationMetrics" class="detail-section">
          <h4>验证结果</h4>
          <div class="validation-info">
            <template v-if="parsedMetrics(detailModel.validationMetrics).cv_mean !== undefined">
              <div class="val-item">
                <span class="val-label">CV {{ parsedMetrics(detailModel.validationMetrics).cv_folds || 5 }}-Fold</span>
                <span class="val-value">{{ (parsedMetrics(detailModel.validationMetrics).cv_mean * 100).toFixed(1) }}% ± {{ (parsedMetrics(detailModel.validationMetrics).cv_std * 100).toFixed(1) }}%</span>
              </div>
              <div v-if="parsedMetrics(detailModel.validationMetrics).cv_scores" class="val-scores">
                <span v-for="(s, i) in parsedMetrics(detailModel.validationMetrics).cv_scores" :key="i" class="score-chip">{{ (s * 100).toFixed(1) }}%</span>
              </div>
            </template>
            <template v-if="parsedMetrics(detailModel.validationMetrics).temporal_split">
              <div class="val-item">
                <span class="val-label">时间外验证</span>
                <span class="val-value">{{ parsedMetrics(detailModel.validationMetrics).temporal_split }}</span>
              </div>
              <div v-if="parsedMetrics(detailModel.validationMetrics).temporal_accuracy" class="val-item">
                <span class="val-label">时序准确率</span>
                <span class="val-value">{{ (parsedMetrics(detailModel.validationMetrics).temporal_accuracy * 100).toFixed(1) }}%</span>
              </div>
            </template>
          </div>
        </div>

        <div v-if="detailModel.status === 'published' && (detailModel.predictInputTable || detailModel.predictResultTable || detailModel.scheduleEnabled)" class="detail-section">
          <h4>发布配置</h4>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item v-if="detailModel.predictInputTable" label="输入表">{{ detailModel.predictInputTable }}</el-descriptions-item>
            <el-descriptions-item v-if="detailModel.predictInputFilter" label="筛选条件">
              <code style="font-size: 12px">{{ detailModel.predictInputFilter }}</code>
            </el-descriptions-item>
            <el-descriptions-item v-if="detailModel.predictResultTable" label="结果表">{{ detailModel.predictResultTable }}</el-descriptions-item>
            <el-descriptions-item label="定时调度">
              <span v-if="detailModel.scheduleEnabled" style="color: var(--el-color-success)">
                {{ { train: '定期重训', predict: '定期预测' }[detailModel.scheduleMode] || detailModel.scheduleMode }}
                ({{ { '*/60': '每小时', '*/1440': '每天', '*/5': '每5分钟', '*/15': '每15分钟', '*/30': '每30分钟', '*/360': '每6小时', '*/720': '每12小时' }[detailModel.scheduleCron] || '每' + detailModel.scheduleCron.replace('*/', '') + '分钟' }})
              </span>
              <span v-else style="color: var(--text-muted)">未启用</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detailModel.lastRunAt" label="上次运行">{{ formatDate(detailModel.lastRunAt) }}</el-descriptions-item>
            <el-descriptions-item v-if="detailModel.nextRunAt" label="下次运行">{{ formatDate(detailModel.nextRunAt) }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="detail-section">
          <h4>执行历史</h4>
          <div v-if="loadingExecutions" style="text-align: center; padding: 16px"><span class="spinner"></span></div>
          <div v-else-if="!executions.length" class="empty-executions">暂无执行记录</div>
          <div v-else class="execution-list">
            <div v-for="exec in executions" :key="exec.id" class="execution-item" :class="{ 'exec-failed-row': exec.status === 'failed' }">
              <div class="exec-row-main">
                <span :class="['exec-status', 'exec-' + exec.status]">{{ execStatusLabel(exec.status) }}</span>
                <span class="exec-time">{{ exec.executionTimeMs ? (exec.executionTimeMs / 1000).toFixed(1) + 's' : '-' }}</span>
                <span class="exec-trigger">{{ execTriggerLabel(exec.triggerType) }}</span>
                <span class="exec-date">{{ formatDate(exec.createdAt) }}</span>
              </div>
              <div v-if="exec.metrics && exec.status === 'success'" class="exec-metrics">
                <template v-for="(val, key) in parsedMetrics(exec.metrics)" :key="key">
                  <span v-if="['accuracy','f1','precision','recall','r2','rmse','mae','silhouette_score'].includes(key)" class="exec-metric-chip">{{ formatMetricName(key) }} {{ formatMetricValue(key, val) }}</span>
                </template>
              </div>
              <div v-if="exec.status === 'failed' && exec.executionLog" class="exec-error">
                {{ exec.executionLog.split('\n').filter(l => l.trim()).pop() }}
              </div>
            </div>
          </div>
        </div>

        <!-- Quick actions in detail drawer -->
        <div class="detail-actions">
          <el-button size="small" type="primary" :loading="trainingId === detailModel.id"
            @click="handleTrain(detailModel.id)">
            {{ detailModel.status === 'training' ? '训练中...' : '训练' }}
          </el-button>
          <el-button v-if="detailModel.status === 'trained' || detailModel.status === 'offline'" size="small" type="success"
            @click="handlePublish(detailModel.id)">发布</el-button>
          <el-button v-if="detailModel.status === 'published'" size="small" type="warning"
            @click="handleOffline(detailModel.id)">下线</el-button>
          <el-button v-if="detailModel.status === 'published' || detailModel.status === 'trained'" size="small" type="primary"
            @click="showDetail = false; openPredict(detailModel)">预测</el-button>
          <el-button size="small" @click="showDetail = false; editModel(detailModel)">调参</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PipelineEditor from './PipelineEditor.vue'
import {
  fetchMiningModels, fetchMiningModel, createMiningModel, updateMiningModel,
  deleteMiningModel, trainMiningModel, publishMiningModel, offlineMiningModel,
  updateModelHyperparams, fetchModelExecutions, fetchDataSources,
  fetchDataSourceTables, fetchTableColumns, updateModelSchedule, predictMiningModel,
  batchPredictMiningModel, validateMiningModel, fetchModelPredictions,
  fetchMiningPipeline, previewResultTable
} from '../api'
import { useAlgorithms } from '../composables/useAlgorithms.js'
import { useMiningStore } from '../stores/mining'

const emit = defineEmits(['close'])

const mining = useMiningStore()

const {
  algorithms, modelTypes, loadAlgorithms,
  getAlgorithmLabel, getAlgorithmsForModelType,
  getAlgorithmParams, getDefaultHyperparams, getModelTypeLabel
} = useAlgorithms()

// Shared state from Pinia store
const models = computed(() => mining.models)
const dataSources = computed(() => mining.dataSources)
const loading = computed(() => mining.loading)
const filterDsId = computed({
  get: () => mining.filterDsId,
  set: (v) => { mining.filterDsId = v }
})
const activeTab = ref('models')
const modelSearch = ref('')
const saving = ref(false)
const trainingId = ref(null)
const savingParams = ref(false)
const loadingExecutions = ref(false)
const pipelineEditorRef = ref(null)

// Table/Column selectors
const tableOptions = ref([])
const columnOptions = ref([])
const loadingTables = ref(false)
const featureChecked = ref({})
const selectAllFeatures = ref(false)

// Create/Edit form
const showCreateDialog = ref(false)
const editingModel = ref(null)
const form = ref(defaultForm())

// Hyperparameter dialog
const showParamsDialog = ref(false)
const paramModel = ref(null)
const paramForm = ref({})
const showAddParamDialog = ref(false)
const newParamKey = ref('')
const newParamValue = ref('')

// Schedule dialog
const showScheduleDialog = ref(false)
const scheduleModel = ref(null)
const scheduleCron = ref('*/60')
const scheduleEnabled = ref(false)
const scheduleMode = ref('train')
const scheduleInputTable = ref('')
const scheduleResultTable = ref('')
const scheduleInputFilter = ref('')

// Batch predict dialog
const showBatchPredictDialog = ref(false)
const batchPredictModel = ref(null)
const batchInputTable = ref('')
const batchResultTable = ref('')
const batchPredictLoading = ref(false)
const batchPredictResult = ref(null)
const resultPreview = ref([])
const resultPreviewColumns = ref([])

// Predict results dialog
const showPredictResultsDialog = ref(false)
const predictResultsModel = ref(null)
const predictionResults = ref([])
const loadingPredictions = ref(false)

// Prediction dialog
const showPredictDialog = ref(false)
const predictModel_ref = ref(null)
const predictInput = ref('[{"dept_id": 1, "salary": 15000}]')
const predictSaveTable = ref('')
const predictLoading = ref(false)
const predictResult = ref(null)

// Publish dialog
const showPublishDialog = ref(false)
const publishModel_ref = ref(null)
const publishLoading = ref(false)
const publishConfig = ref({
  predictInputTable: '',
  predictInputFilter: '',
  predictResultTable: '',
  scheduleEnabled: false,
  scheduleCron: '*/60',
  scheduleMode: 'predict'
})

// Detail drawer
const showDetail = ref(false)
const detailModel = ref(null)
const executions = ref([])
const detailPipelineNodes = ref([])

function defaultForm() {
  const firstModelType = modelTypes.value.length > 0 ? modelTypes.value[0].id : 'classification'
  const firstAlgo = algorithms.value.length > 0 ? algorithms.value[0].algorithmId : 'random_forest'
  return {
    name: '', dataSourceId: null, sourceTable: '', modelType: firstModelType,
    algorithm: firstAlgo, featureColumnsList: [], targetColumn: '',
    description: '',
    preprocessing: { handleMissing: 'drop', encoding: 'label', scaling: 'none' },
    validationMode: 'train_test', cvFolds: 5, testSize: 0.2, temporalColumn: ''
  }
}

const statusLabels = {
  draft: '草稿', training: '训练中', trained: '已训练', trained_failed: '训练失败',
  published: '已发布', offline: '已下线', failed: '失败'
}
function statusLabel(s) { return statusLabels[s] || s }

function execStatusLabel(s) {
  return { success: '成功', failed: '失败', running: '运行中', pending: '等待中' }[s] || s
}
function execTriggerLabel(t) {
  return { manual: '手动', schedule: '定时', chat: '对话' }[t] || t
}

const algorithmLabel = getAlgorithmLabel

function pipelineNodeIcon(type) {
  return { data_source: '📥', preprocessing: '🔧', fill_missing: '🩹', feature_engineering: '⚙️', training: '🧠', evaluation: '📊', output: '💾' }[type] || '📦'
}

function pipelineNodeTitle(node) {
  if (node.type === 'training' && node.config?.algorithm) return algorithmLabel(node.config.algorithm)
  const titles = { data_source: '数据接入', preprocessing: '预处理', fill_missing: '填充缺失', feature_engineering: '特征工程', training: '训练', evaluation: '评估', output: '输出' }
  return titles[node.type] || node.type
}

function pipelineNodeSummary(node) {
  const c = node.config || {}
  switch (node.type) {
    case 'data_source': return c.table || '未配置'
    case 'preprocessing': return [c.handleMissing !== 'none' ? '处理缺失' : '', c.encoding !== 'none' ? '编码' : '', c.scaling !== 'none' ? '缩放' : ''].filter(Boolean).join('+') || '默认'
    case 'fill_missing': return { auto: '自动', mean: '均值', median: '中位数', mode: '众数' }[c.strategy] || '自动'
    case 'feature_engineering': {
      const fc = c.featureColumns ? (typeof c.featureColumns === 'string' ? JSON.parse(c.featureColumns) : c.featureColumns) : []
      return fc.length ? `${fc.length}特征` : '未配置'
    }
    case 'training': return c.hyperparams ? Object.entries(c.hyperparams).slice(0, 2).map(([k, v]) => `${k}=${v}`).join(', ') : ''
    case 'evaluation': {
      const vm = c.validationMode
      if (vm === 'temporal') return `时间外验证 (${c.temporalColumn || '?'})`
      if (vm === 'cv') return `${c.cvFold || 5}-Fold CV`
      if (vm === 'oos') return `OOS ${c.cvFold || 5}-Fold + ${c.testSize || 20}%测试`
      return `${c.testSize || 20}%测试`
    }
    case 'output': return c.table || '未配置'
    default: return ''
  }
}

const featureIndeterminate = computed(() => {
  const total = columnOptions.value.length
  const selected = form.value.featureColumnsList.length
  return selected > 0 && selected < total
})

const filteredAlgorithms = computed(() => {
  return getAlgorithmsForModelType(form.value.modelType)
})

const filteredModels = computed(() => {
  if (!modelSearch.value) return models.value
  const q = modelSearch.value.toLowerCase()
  return models.value.filter(m =>
    m.name?.toLowerCase().includes(q) ||
    m.algorithm?.toLowerCase().includes(q) ||
    m.sourceTable?.toLowerCase().includes(q) ||
    m.modelType?.toLowerCase().includes(q) ||
    m.description?.toLowerCase().includes(q)
  )
})

const modelTypeLabel = getModelTypeLabel

function isPrimary(key, modelType) {
  if (modelType === 'regression') return key === 'r2'
  if (modelType === 'clustering') return key === 'inertia'
  return key === 'accuracy'
}

function primaryMetricLabel(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === 'regression') return m.r2 !== undefined ? 'R²' : 'RMSE'
  if (model.modelType === 'clustering') return '聚类'
  return m.accuracy !== undefined ? '准确率' : 'F1'
}

function primaryMetricValue(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === 'regression') return m.r2 !== undefined ? (m.r2 * 100).toFixed(1) + '%' : m.rmse?.toFixed(4) || '-'
  if (model.modelType === 'clustering') return (m.n_clusters || '-') + ' 类'
  const key = m.accuracy !== undefined ? 'accuracy' : 'f1'
  return m[key] !== undefined ? (m[key] * 100).toFixed(1) + '%' : '-'
}

function primaryMetricRaw(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === 'regression') return m.r2 ?? m.rmse ?? null
  const key = m.accuracy !== undefined ? 'accuracy' : 'f1'
  return m[key] ?? null
}

function metricQuality(val, modelType, key) {
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

function modelTypeIcon(t) {
  return { classification: '🏷️', regression: '📈', clustering: '🎯', anomaly_detection: '🔍' }[t] || '🤖'
}

function formatMetricValue(key, val) {
  if (val == null) return '-'
  const pctKeys = ['accuracy', 'precision', 'recall', 'f1', 'r2']
  return pctKeys.includes(key) ? (val * 100).toFixed(1) + '%' : Number(val).toFixed(4)
}
function formatMetricName(key) {
  const names = { accuracy: '准确率', precision: '精确率', recall: '召回率', f1: 'F1', mse: 'MSE', rmse: 'RMSE', r2: 'R²' }
  return names[key] || key
}
function parsedMetrics(json) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || {} } catch { return {} }
}
function parseJson(json, fallback) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || fallback } catch { return fallback }
}
function formatDate(d) { return d ? new Date(d).toLocaleString('zh-CN') : '-' }

const cronLabelMap = { '*/5': '每5分钟', '*/15': '每15分钟', '*/30': '每30分钟', '*/60': '每小时', '*/360': '每6小时', '*/720': '每12小时', '*/1440': '每天', '*/10080': '每周' }
function scheduleIntervalLabel(model) {
  return cronLabelMap[model.scheduleCron] || '定期'
}
function scheduleTooltip(model) {
  const mode = { train: '定期重训', predict: '定期预测' }[model.scheduleMode] || model.scheduleMode
  const interval = cronLabelMap[model.scheduleCron] || model.scheduleCron
  let tip = `${mode} · ${interval}`
  if (model.nextRunAt) tip += `\n下次运行: ${formatDate(model.nextRunAt)}`
  return tip
}

const sortedImportance = computed(() => {
  if (!detailModel.value?.featureImportance) return {}
  const parsed = parseJson(detailModel.value.featureImportance, {})
  return Object.entries(parsed).sort((a, b) => b[1] - a[1]).slice(0, 10).reduce((acc, [k, v]) => { acc[k] = v; return acc }, {})
})
const maxImportance = computed(() => {
  const vals = Object.values(sortedImportance.value)
  return vals.length ? Math.max(...vals) : 1
})

const predictRows = computed(() => {
  if (!predictResult.value) return []
  let inputRows = []
  try { inputRows = JSON.parse(predictInput.value) } catch { return [] }
  const preds = predictResult.value.predictions || []
  return inputRows.map((row, i) => ({ ...row, prediction: preds[i], probability: predictResult.value.probabilities?.[i] ? (Math.max(...predictResult.value.probabilities[i]) * 100).toFixed(1) + '%' : '' }))
})

async function loadModels() {
  await mining.loadModels()
}

function algorithmParams(algo) {
  return getAlgorithmParams(algo)
}

async function onDataSourceChange(dsId) {
  form.value.sourceTable = ''
  form.value.featureColumnsList = []
  form.value.targetColumn = ''
  columnOptions.value = []
  featureChecked.value = {}
  if (!dsId) { tableOptions.value = []; return }
  loadingTables.value = true
  try {
    tableOptions.value = await fetchDataSourceTables(dsId) || []
  } catch { tableOptions.value = [] }
  finally { loadingTables.value = false }
}

async function onTableChange(tableName) {
  form.value.featureColumnsList = []
  form.value.targetColumn = ''
  featureChecked.value = {}
  if (!tableName || !form.value.dataSourceId) { columnOptions.value = []; return }
  try {
    columnOptions.value = await fetchTableColumns(form.value.dataSourceId, tableName) || []
  } catch { columnOptions.value = [] }
}

function onSelectAllFeatures(checked) {
  const cols = columnOptions.value.map(c => c.name)
  if (checked) {
    form.value.featureColumnsList = [...cols]
    const map = {}
    cols.forEach(c => { map[c] = true })
    featureChecked.value = map
  } else {
    form.value.featureColumnsList = []
    featureChecked.value = {}
  }
}

function syncFeatureColumns() {
  form.value.featureColumnsList = Object.entries(featureChecked.value)
    .filter(([, v]) => v).map(([k]) => k)
  const total = columnOptions.value.length
  selectAllFeatures.value = form.value.featureColumnsList.length === total
}

watch(filterDsId, () => loadModels())
watch(() => form.value.modelType, (mt) => {
  const available = getAlgorithmsForModelType(mt)
  if (available.length && !available.find(a => a.algorithmId === form.value.algorithm)) {
    form.value.algorithm = available[0].algorithmId
  }
})
onMounted(() => {
  loadAlgorithms()
  loadModels()
})

async function handleTrain(id) {
  trainingId.value = id
  try {
    const model = await trainMiningModel(id)
    mining.updateModelInList(model)

    if (model.status === 'training') {
      ElMessage.info('训练已启动，正在监听状态...')
      const es = mining.watchModelStatus(id)
      await new Promise((resolve) => {
        const unwatch = watch(
          () => mining.models.find(m => m.id === id),
          (m) => {
            if (m && ['trained', 'failed', 'trained_failed', 'published'].includes(m.status)) {
              showTrainingResult(m)
              unwatch()
              resolve()
            }
          },
          { deep: true }
        )
        // Safety timeout — fallback to polling after 5 min
        setTimeout(() => { unwatch(); resolve() }, 300000)
      })
    } else {
      showTrainingResult(model)
    }
    if (detailModel.value?.id === id) detailModel.value = mining.models.find(m => m.id === id) || model
  } catch (e) {
    ElMessage.error('训练失败: ' + (e.message || '未知错误'))
  } finally {
    trainingId.value = null
  }
}


function showTrainingResult(model) {
  if (model.status === 'trained') {
    const metrics = parseJson(model.metrics, {})
    const primaryKeys = ['accuracy', 'f1', 'r2']
    const primary = primaryKeys.find(k => metrics[k] != null)
    if (primary) {
      const val = metrics[primary]
      const pct = ['accuracy', 'f1', 'precision', 'recall', 'r2'].includes(primary)
      ElMessage.success(`训练完成！${formatMetricName(primary)} = ${pct ? (val * 100).toFixed(1) + '%' : val.toFixed(4)}`)
    } else {
      ElMessage.success('训练完成！')
    }
  } else if (model.status === 'failed' || model.status === 'trained_failed') {
    ElMessage.error('训练失败，请查看执行历史了解详情')
  } else {
    ElMessage.info('训练状态: ' + (statusLabels[model.status] || model.status))
  }
}

async function handlePublish(id) {
  const model = models.value.find(m => m.id === id)
  if (!model) return
  publishModel_ref.value = model
  publishConfig.value = {
    predictInputTable: model.predictInputTable || model.sourceTable || '',
    predictInputFilter: model.predictInputFilter || '',
    predictResultTable: model.predictResultTable || '',
    scheduleEnabled: !!model.scheduleEnabled,
    scheduleCron: model.scheduleCron || '*/60',
    scheduleMode: model.scheduleMode || 'predict'
  }
  showPublishDialog.value = true
  // Load tables for this model's data source
  if (model.dataSourceId) {
    fetchDataSourceTables(model.dataSourceId).then(tables => {
      tableOptions.value = tables || []
    }).catch(() => {})
  }
}

async function confirmPublish() {
  if (!publishModel_ref.value) return
  publishLoading.value = true
  try {
    const config = {
      predictInputTable: publishConfig.value.predictInputTable || null,
      predictInputFilter: publishConfig.value.predictInputFilter || null,
      predictResultTable: publishConfig.value.predictResultTable || null,
      scheduleEnabled: publishConfig.value.scheduleEnabled,
      ...(publishConfig.value.scheduleEnabled ? {
        scheduleCron: publishConfig.value.scheduleCron,
        scheduleMode: publishConfig.value.scheduleMode
      } : {})
    }
    const model = await publishMiningModel(publishModel_ref.value.id, config)
    mining.updateModelInList(model)
    if (detailModel.value?.id === publishModel_ref.value.id) detailModel.value = model
    showPublishDialog.value = false
    ElMessage.success(publishConfig.value.scheduleEnabled
      ? '模型已发布，定时调度已启用'
      : '模型已发布')
  } catch (e) {
    ElMessage.error(e.message || '发布失败')
  } finally {
    publishLoading.value = false
  }
}

async function handleOffline(id) {
  try {
    const model = await offlineMiningModel(id)
    mining.updateModelInList(model)
    ElMessage.success('模型已下线')
    if (detailModel.value?.id === id) detailModel.value = model
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function handleDelete(id, name) {
  try {
    await ElMessageBox.confirm(`确定删除模型「${name}」？此操作不可撤销。`, '删除模型', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
    await deleteMiningModel(id)
    mining.removeModel(id)
    if (detailModel.value?.id === id) showDetail.value = false
    ElMessage.success('已删除')
  } catch { /* cancelled */ }
}

function openSchedule(model) {
  scheduleModel.value = model
  scheduleCron.value = model.scheduleCron || '*/60'
  scheduleEnabled.value = !!model.scheduleEnabled
  scheduleMode.value = model.scheduleMode || 'train'
  scheduleInputTable.value = model.predictInputTable || ''
  scheduleResultTable.value = model.predictResultTable || ''
  scheduleInputFilter.value = model.predictInputFilter || ''
  showScheduleDialog.value = true
  // Load tables for this model's data source
  if (model.dataSourceId) {
    fetchDataSourceTables(model.dataSourceId).then(tables => {
      tableOptions.value = tables || []
    }).catch(() => {})
  }
}

async function saveSchedule() {
  if (!scheduleModel.value) return
  try {
    const updates = {
      scheduleCron: scheduleCron.value,
      scheduleEnabled: scheduleEnabled.value,
      scheduleMode: scheduleMode.value
    }
    if (scheduleMode.value === 'predict') {
      updates.predictInputTable = scheduleInputTable.value
      updates.predictResultTable = scheduleResultTable.value
    }
    const updated = await updateModelSchedule(
      scheduleModel.value.id,
      scheduleCron.value,
      scheduleEnabled.value,
      scheduleMode.value
    )
    // Also update predict tables if predict mode
    if (scheduleMode.value === 'predict' && scheduleInputTable.value) {
      await updateMiningModel(scheduleModel.value.id, {
        predictInputTable: scheduleInputTable.value,
        predictResultTable: scheduleResultTable.value,
        predictInputFilter: scheduleInputFilter.value
      })
    }
    mining.updateModelInList({ ...mining.models.find(m => m.id === scheduleModel.value.id), ...updated })
    ElMessage.success(scheduleEnabled.value ? '调度已启用' : '调度已更新')
    showScheduleDialog.value = false
  } catch (e) {
    ElMessage.error('保存调度失败: ' + (e.message || ''))
  }
}

function editModel(model) {
  paramModel.value = model
  const definedParams = algorithmParams(model.algorithm)
  const savedParams = (() => {
    try { return typeof model.hyperparameters === 'string' ? JSON.parse(model.hyperparameters) : model.hyperparameters || {} } catch { return {} }
  })()
  const formMap = {}
  for (const p of definedParams) {
    const raw = savedParams[p.key] !== undefined ? savedParams[p.key] : (p.defaultValue !== undefined && p.defaultValue !== null ? p.defaultValue : (p.type === 'int' ? 100 : p.type === 'float' ? 0.1 : p.type === 'select' ? (p.options?.[0] || '') : ''))
    if (p.type === 'float' && typeof raw === 'number') {
      formMap[p.key] = Math.round(raw * 10000) / 10000
    } else {
      formMap[p.key] = raw
    }
  }
  // Add any custom params not in the definition
  for (const [k, v] of Object.entries(savedParams)) {
    if (!(k in formMap)) formMap[k] = v
  }
  paramForm.value = formMap
  showParamsDialog.value = true
}

function confirmAddParam() {
  if (!newParamKey.value.trim()) return
  paramForm.value[newParamKey.value.trim()] = newParamValue.value
  newParamKey.value = ''
  newParamValue.value = ''
  showAddParamDialog.value = false
}

async function handleSaveParams() {
  if (!paramModel.value) return
  savingParams.value = true
  try {
    const paramsToSave = { ...paramForm.value }
    const definedParams = algorithmParams(paramModel.value.algorithm)
    for (const p of definedParams) {
      if (p.type === 'float' && typeof paramsToSave[p.key] === 'number') {
        paramsToSave[p.key] = Math.round(paramsToSave[p.key] * 10000) / 10000
      }
    }
    const model = await updateModelHyperparams(paramModel.value.id, paramsToSave)
    mining.updateModelInList(model)
    showParamsDialog.value = false
    ElMessage.success('参数已更新')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || '未知错误'))
  } finally {
    savingParams.value = false
  }
}

function resetForm() {
  editingModel.value = null
  const ds = dataSources.value.find(d => d.databaseName !== 'smart_query') || dataSources.value[0]
  form.value = { ...defaultForm(), dataSourceId: ds?.id || null }
}

watch(showCreateDialog, (v) => {
  if (v) {
    const ds = dataSources.value.find(d => d.databaseName !== 'smart_query') || dataSources.value[0]
    form.value = { ...defaultForm(), dataSourceId: ds?.id || null }
    if (ds?.id) onDataSourceChange(ds.id)
  } else {
    editingModel.value = null
    form.value = defaultForm()
    tableOptions.value = []
    columnOptions.value = []
    featureChecked.value = {}
  }
})

async function handleSave() {
  if (!form.value.name || !form.value.dataSourceId || !form.value.sourceTable || !form.value.algorithm) {
    ElMessage.warning('请填写必填字段')
    return
  }
  if (!form.value.featureColumnsList.length) {
    ElMessage.warning('请至少选择一个特征列')
    return
  }
  if (form.value.modelType !== 'clustering' && !form.value.targetColumn) {
    ElMessage.warning('请选择目标列')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name,
      dataSourceId: form.value.dataSourceId,
      sourceTable: form.value.sourceTable,
      modelType: form.value.modelType,
      algorithm: form.value.algorithm,
      featureColumns: JSON.stringify(form.value.featureColumnsList),
      targetColumn: form.value.targetColumn,
      hyperparameters: '{}',
      description: form.value.description,
      preprocessing: JSON.stringify(form.value.preprocessing),
      validationMode: form.value.validationMode || 'train_test',
      cvFolds: form.value.cvFolds || 5,
      testSize: form.value.testSize || 0.2,
      temporalColumn: form.value.temporalColumn || null
    }
    if (editingModel.value) {
      const updated = await updateMiningModel(editingModel.value.id, payload)
      mining.updateModelInList(updated)
      ElMessage.success('已更新')
    } else {
      const created = await createMiningModel(payload)
      mining.addModel(created)
      ElMessage.success('已创建')
    }
    showCreateDialog.value = false
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

async function handleSaveAndTrain() {
  if (!form.value.name || !form.value.dataSourceId || !form.value.sourceTable || !form.value.algorithm) {
    ElMessage.warning('请填写必填字段')
    return
  }
  if (!form.value.featureColumnsList.length) {
    ElMessage.warning('请至少选择一个特征列')
    return
  }
  if (form.value.modelType !== 'clustering' && !form.value.targetColumn) {
    ElMessage.warning('请选择目标列')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name,
      dataSourceId: form.value.dataSourceId,
      sourceTable: form.value.sourceTable,
      modelType: form.value.modelType,
      algorithm: form.value.algorithm,
      featureColumns: JSON.stringify(form.value.featureColumnsList),
      targetColumn: form.value.targetColumn,
      hyperparameters: '{}',
      description: form.value.description,
      preprocessing: JSON.stringify(form.value.preprocessing),
      validationMode: form.value.validationMode || 'train_test',
      cvFolds: form.value.cvFolds || 5,
      testSize: form.value.testSize || 0.2,
      temporalColumn: form.value.temporalColumn || null
    }
    const created = await createMiningModel(payload)
    mining.addModel(created)
    showCreateDialog.value = false
    ElMessage.success('已创建，开始训练...')
    trainingId.value = created.id
    const trained = await trainMiningModel(created.id)
    mining.updateModelInList(trained)
    if (trained.status === 'trained') ElMessage.success('训练完成！')
    else ElMessage.warning('训练未成功: ' + (trained.status || '未知'))
  } catch (e) {
    ElMessage.error('操作失败: ' + (e.message || ''))
  } finally {
    saving.value = false
    trainingId.value = null
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

function onActionCmd(cmd, model) {
  if (cmd === 'params') editModel(model)
  else if (cmd === 'validate') handleValidate(model.id)
  else if (cmd === 'schedule') openSchedule(model)
  else if (cmd === 'predict') openPredict(model)
  else if (cmd === 'batchPredict') openBatchPredict(model)
  else if (cmd === 'predictResults') openPredictResults(model)
  else if (cmd === 'viewPipeline') goToPipeline(model.pipelineId)
  else if (cmd === 'delete') handleDelete(model.id, model.name)
}

function openBatchPredict(model) {
  batchPredictModel.value = model
  batchInputTable.value = ''
  batchResultTable.value = ''
  batchPredictResult.value = null
  resultPreview.value = []
  resultPreviewColumns.value = []
  showBatchPredictDialog.value = true
  // Load tables for this model's data source
  if (model.dataSourceId) {
    fetchDataSourceTables(model.dataSourceId).then(tables => {
      tableOptions.value = tables || []
    }).catch(() => {})
  }
}

async function handleBatchPredict() {
  if (!batchInputTable.value) {
    ElMessage.warning('请选择输入表')
    return
  }
  // Save predict config to model first
  try {
    await updateMiningModel(batchPredictModel.value.id, {
      predictInputTable: batchInputTable.value,
      predictResultTable: batchResultTable.value || null
    })
  } catch { /* ignore */ }

  batchPredictLoading.value = true
  batchPredictResult.value = null
  try {
    const result = await batchPredictMiningModel(batchPredictModel.value.id)
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

function truncateStr(str, max) {
  if (!str) return '-'
  return str.length > max ? str.substring(0, max) + '...' : str
}

function openPredict(model) {
  predictModel_ref.value = model
  predictInput.value = '[\n  '
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
      predictModel_ref.value.id,
      inputRows,
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

function goToPipeline(pipelineId) {
  showDetail.value = false
  activeTab.value = 'pipeline'
  nextTick(() => {
    pipelineEditorRef.value?.openPipelineById(pipelineId)
  })
}
</script>

<style scoped>
.mining-manager {
  flex: 1; min-width: 0;
  background: var(--bg); display: flex; flex-direction: column;
}
.mining-tabs {
  flex: 1; display: flex; flex-direction: column; overflow: hidden;
  padding: 0 var(--space-xl);
}
.mining-tabs :deep(.el-tabs__content) { flex: 1; overflow: auto; }
.mining-tabs :deep(.el-tab-pane) { height: 100%; }
.tab-toolbar { display: flex; justify-content: flex-end; margin-bottom: 12px; }
.mining-header {
  height: 52px; background: var(--surface); border-bottom: 1px solid var(--border);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 var(--space-xl); flex-shrink: 0;
}
.header-left { display: flex; align-items: center; gap: var(--space-md); }
.back-btn {
  background: none; border: none; cursor: pointer; font-size: var(--font-md);
  color: var(--primary); display: flex; align-items: center; gap: 4px;
  padding: 4px 8px; border-radius: var(--radius-md);
  transition: background 0.15s;
}
.back-btn:hover { background: var(--primary-light); }
.back-arrow { font-size: var(--font-lg); }
.page-title { font-size: var(--font-xl); font-weight: 600; color: var(--text-primary); }
.header-actions { display: flex; align-items: center; gap: var(--space-sm); }

.mining-body {
  flex: 1; overflow-y: auto;
}
.mining-loading, .mining-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 60px 0; color: var(--text-muted); font-size: var(--font-md);
}
.empty-icon { font-size: 48px; margin-bottom: var(--space-md); }
.empty-hint { font-size: var(--font-sm); color: var(--text-muted); margin-top: var(--space-xs); }

.model-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: var(--space-md);
}
.model-card {
  background: var(--surface); border-radius: var(--radius-lg); border: 1px solid var(--border);
  padding: var(--space-lg); cursor: pointer; transition: all 0.2s;
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
}
.model-card:hover { border-color: var(--primary); box-shadow: 0 4px 12px rgba(0,0,0,0.08); transform: translateY(-1px); }
.model-card-header {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--space-sm);
}
.model-card-title { display: flex; align-items: center; gap: 6px; min-width: 0; }
.model-type-icon { font-size: 16px; flex-shrink: 0; }
.model-name { font-size: var(--font-lg); font-weight: 600; color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model-card-body { margin-bottom: var(--space-md); }
.model-desc {
  font-size: var(--font-sm); color: var(--text-muted); margin-top: var(--space-xs);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%;
}
.model-meta { display: flex; gap: var(--space-sm); margin-bottom: var(--space-xs); }
.meta-item { font-size: var(--font-sm); color: var(--text-secondary); }
.meta-item.secondary { color: var(--text-muted); font-size: var(--font-xs); }
.model-metrics { display: flex; flex-wrap: wrap; gap: var(--space-xs); margin-top: var(--space-sm); align-items: center; }
.metric-primary {
  padding: 2px 10px; border-radius: var(--radius-sm);
  font-size: var(--font-sm); font-weight: 500;
  display: inline-flex; align-items: center; gap: 4px;
  color: #fff;
}
.metric-primary.quality-good { background: var(--color-success); }
.metric-primary.quality-moderate { background: var(--color-warning); }
.metric-primary.quality-poor { background: var(--color-danger); }
.metric-primary.quality-neutral { background: var(--color-success); }
.metric-primary strong { font-size: var(--font-lg); }
.metric-chip {
  padding: 2px var(--space-sm); border-radius: var(--radius-sm);
  font-size: var(--font-xs); font-weight: 500;
}
.metric-chip.quality-good { background: var(--color-success-light); color: var(--color-success); }
.metric-chip.quality-moderate { background: var(--color-warning-light); color: var(--color-warning); }
.metric-chip.quality-poor { background: var(--color-danger-light); color: var(--color-danger); }
.metric-chip.quality-neutral { background: var(--color-success-light); color: var(--color-success); }
.model-card-actions {
  display: flex; gap: var(--space-xs); border-top: 1px solid var(--border-light);
  padding-top: var(--space-sm);
}

.status-badge {
  display: inline-block; padding: 2px 8px; border-radius: var(--radius-pill);
  font-size: var(--font-xs); font-weight: 500;
}
.status-draft { background: var(--color-info-light); color: var(--color-info); }
.status-training { background: var(--color-warning-light); color: var(--color-warning); }
.status-trained { background: var(--color-success-light); color: var(--color-success); }
.status-published { background: var(--primary-light); color: var(--primary); }
.status-offline { background: var(--border-light); color: var(--text-muted); }
.status-failed { background: var(--color-danger-light); color: var(--color-danger); }
.schedule-badge {
  display: inline-flex; align-items: center; gap: 2px;
  padding: 2px 8px; border-radius: var(--radius-pill);
  font-size: var(--font-xs); font-weight: 500;
  background: #e6f7ff; color: #1890ff; cursor: default;
}

/* Detail drawer */
.detail-section { margin-top: var(--space-lg); }
.detail-section h4 {
  font-size: var(--font-base); font-weight: 600; color: var(--text-primary);
  margin-bottom: var(--space-sm); padding-bottom: var(--space-xs);
  border-bottom: 1px solid var(--border-light);
}
.feature-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.pipeline-mini-flow {
  display: flex; align-items: flex-start; gap: 2px; overflow-x: auto;
  padding: 8px 0; flex-wrap: nowrap;
}
.mini-flow-node {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  background: var(--bg-secondary); border: 1px solid var(--border-light);
  border-radius: 6px; padding: 6px 8px; min-width: 60px; max-width: 80px;
  text-align: center; flex-shrink: 0;
}
.mini-flow-icon { font-size: 16px; }
.mini-flow-title { font-size: 10px; font-weight: 600; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 70px; }
.mini-flow-detail { font-size: 9px; color: var(--text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 70px; }
.mini-flow-arrow { color: var(--text-muted); font-size: 12px; margin-top: 14px; flex-shrink: 0; }
.metrics-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-sm); }
.metric-card {
  background: var(--color-success-light); border-radius: var(--radius-md);
  padding: var(--space-sm); text-align: center;
}
.metric-value { display: block; font-size: var(--font-xl); font-weight: 700; color: var(--color-success); }
.metric-name { font-size: var(--font-xs); color: var(--text-muted); }
.importance-list { display: flex; flex-direction: column; gap: var(--space-xs); }
.validation-info { display: flex; flex-direction: column; gap: 6px; }
.val-item { display: flex; justify-content: space-between; font-size: var(--font-sm); }
.val-label { color: var(--text-secondary); }
.val-value { font-weight: 600; color: var(--text-primary); }
.val-scores { display: flex; flex-wrap: wrap; gap: 4px; }
.score-chip { font-size: 11px; background: var(--primary-light); color: var(--primary); padding: 1px 6px; border-radius: var(--radius-sm); }
.importance-bar { display: flex; align-items: center; gap: var(--space-sm); }
.imp-label { width: 100px; font-size: var(--font-sm); color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.imp-track { flex: 1; height: 8px; background: var(--border-light); border-radius: 4px; overflow: hidden; }
.imp-fill { height: 100%; background: var(--primary); border-radius: 4px; transition: width 0.3s; }
.imp-value { width: 40px; font-size: var(--font-sm); color: var(--text-muted); text-align: right; }

.predict-result { margin-top: 16px; }
.predict-result h4 { font-size: var(--font-md); color: var(--text-primary); margin-bottom: 8px; }

.execution-list { display: flex; flex-direction: column; gap: var(--space-xs); }
.execution-item {
  padding: var(--space-xs) var(--space-sm); background: var(--border-lighter);
  border-radius: var(--radius-sm); font-size: var(--font-sm);
}
.exec-row-main {
  display: flex; align-items: center; gap: var(--space-sm);
}
.exec-failed-row {
  flex-direction: column; align-items: stretch;
}
.exec-error {
  margin-top: 4px; padding: 4px 8px; font-size: 11px; color: var(--color-danger);
  background: rgba(245, 108, 108, 0.08); border-radius: 4px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.exec-metrics {
  display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px;
}
.exec-metric-chip {
  font-size: 11px; padding: 1px 6px; border-radius: var(--radius-sm);
  background: var(--color-success-light); color: var(--color-success); font-weight: 500;
}
.exec-status { font-weight: 500; }
.exec-success { color: var(--color-success); }
.exec-failed { color: var(--color-danger); }
.exec-running { color: var(--color-warning); }

.detail-actions {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.exec-pending { color: var(--color-info); }
.exec-time { color: var(--text-secondary); }
.exec-trigger { color: var(--text-muted); }
.exec-date { margin-left: auto; color: var(--text-muted); font-size: var(--font-xs); }
.empty-executions { text-align: center; color: var(--text-muted); padding: var(--space-lg); }

/* Params editor */
.params-editor { padding: var(--space-sm) 0; }
.params-model-name { font-weight: 600; margin-bottom: var(--space-md); color: var(--text-primary); }
.param-row { display: flex; align-items: center; gap: var(--space-sm); margin-bottom: var(--space-sm); }
.param-label { width: 160px; font-size: var(--font-sm); color: var(--text-secondary); text-align: right; flex-shrink: 0; }
.param-hint { display: block; font-size: 11px; color: var(--text-muted); }

/* Column picker */
.col-hint { color: var(--text-muted); font-size: var(--font-sm); padding: 8px 0; }
.column-picker { width: 100%; }
.column-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px 12px;
  max-height: 200px; overflow-y: auto; border: 1px solid var(--border-light);
  border-radius: var(--radius-md); padding: var(--space-sm);
}
.col-name { font-size: var(--font-sm); }
.col-type { font-size: 11px; color: var(--text-muted); margin-left: 4px; }
.selected-count { font-size: 12px; color: var(--text-muted); margin-top: 4px; }

.spinner {
  width: 14px; height: 14px; border: 2px solid var(--border); border-top-color: var(--primary);
  border-radius: 50%; animation: spin 0.6s linear infinite; display: inline-block;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
