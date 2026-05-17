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
            <span v-if="model.status === MODEL_STATUS.PUBLISHED && model.scheduleEnabled" class="schedule-badge" :title="scheduleTooltip(model)">⏰ {{ scheduleIntervalLabel(model) }}</span>
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
              <el-tag v-if="needsSync(model)" size="small" effect="dark" type="warning" style="cursor:pointer" @click="handleSyncPipeline(model)">未同步</el-tag>
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
            <el-button size="small" :loading="trainingId === model.id" @click.stop="doTrain(model.id)">
              {{ model.status === MODEL_STATUS.TRAINING ? '训练中...' : '训练' }}
            </el-button>
            <el-button v-if="model.status !== MODEL_STATUS.DRAFT && model.status !== MODEL_STATUS.TRAINING" size="small"
              @click.stop="editModel(model)">调参</el-button>
            <el-button v-if="model.status === MODEL_STATUS.TRAINED || model.status === MODEL_STATUS.OFFLINE" size="small" type="success"
              @click.stop="doPublish(model.id)">发布</el-button>
            <el-button v-if="model.status === MODEL_STATUS.PUBLISHED" size="small" type="warning"
              @click.stop="doOffline(model.id)">下线</el-button>
            <el-button v-if="model.status === MODEL_STATUS.PUBLISHED || model.status === MODEL_STATUS.TRAINED" size="small" type="primary"
              @click.stop="openPredict(model)">预测</el-button>
            <el-button v-if="model.status === MODEL_STATUS.PUBLISHED" size="small" plain
              @click.stop="openBatchPredict(model)">批量预测</el-button>
            <el-dropdown trigger="click" @command="cmd => onActionCmd(cmd, model)" @click.stop>
              <el-button size="small" @click.stop>更多 ▾</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="model.status === MODEL_STATUS.DRAFT" command="validate">训练前校验</el-dropdown-item>
                  <el-dropdown-item v-if="model.status === MODEL_STATUS.PUBLISHED" command="batchPredict">批量预测</el-dropdown-item>
                  <el-dropdown-item command="schedule">{{ model.scheduleEnabled ? '调度设置 (已启用)' : '调度设置' }}</el-dropdown-item>
                  <el-dropdown-item v-if="model.status === MODEL_STATUS.PUBLISHED || model.status === MODEL_STATUS.TRAINED" command="predictResults">预测记录</el-dropdown-item>
                  <el-dropdown-item v-if="model.pipelineId" command="viewPipeline">查看流程</el-dropdown-item>
                  <el-dropdown-item v-if="model.pipelineId" command="syncPipeline">同步流程</el-dropdown-item>
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
        <PipelineEditor ref="pipelineEditorRef" :dataSources="dataSources" @goToModel="goToModel" />
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
        <el-form-item v-if="form.modelType !== MODEL_TYPE.CLUSTERING" label="目标列">
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
              <el-option v-for="t in scheduleTableOptions" :key="t.name" :label="t.name" :value="t.name" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="scheduleMode === 'predict'" label="结果表">
            <el-input v-model="scheduleResultTable" placeholder="预测结果保存表名" />
          </el-form-item>
          <el-form-item label="调度间隔">
            <el-select v-model="scheduleCron" style="width: 100%" :teleported="false">
              <el-option label="每 30 分钟" value="*/30 * * * *" />
              <el-option label="每 1 小时" value="0 * * * *" />
              <el-option label="每天早上 6:00" value="0 6 * * *" />
              <el-option label="每天早上 8:00" value="0 8 * * *" />
              <el-option label="每天午夜" value="0 0 * * *" />
              <el-option label="每周一 8:00" value="0 8 * * 1" />
              <el-option label="每月 1 号" value="0 0 1 * *" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="scheduleMode === 'predict'" label="筛选条件">
            <el-input v-model="scheduleInputFilter" placeholder="如: etl_date &lt;= '${etl_date}' 或 status = 'active'" />
            <div class="var-helper">
              <span class="var-label">可用变量:</span>
              <code class="var-chip" @click="scheduleInputFilter += '${etl_date}'">${etl_date}</code>
              <code class="var-chip" @click="scheduleInputFilter += '${today}'">${today}</code>
              <code class="var-chip" @click="scheduleInputFilter += '${yesterday}'">${yesterday}</code>
              <code class="var-chip" @click="scheduleInputFilter += '${today-7}'">${today-N}</code>
            </div>
          </el-form-item>
        </el-form>
        <div v-if="scheduleModel.lastRunAt" style="margin-top: 8px; font-size: 12px; color: var(--text-muted)">
          上次运行: {{ new Date(scheduleModel.lastRunAt).toLocaleString('zh-CN') }}
        </div>
      </div>
      <template #footer>
        <el-button @click="showScheduleDialog = false">取消</el-button>
        <el-button type="primary" @click="doSaveSchedule">保存</el-button>
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
              <el-option v-for="t in publishTableOptions" :key="t.name" :label="t.name" :value="t.name">
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
              <el-option label="每 30 分钟" value="*/30 * * * *" />
              <el-option label="每 1 小时" value="0 * * * *" />
              <el-option label="每天早上 6:00" value="0 6 * * *" />
              <el-option label="每天早上 8:00" value="0 8 * * *" />
              <el-option label="每天午夜" value="0 0 * * *" />
              <el-option label="每周一 8:00" value="0 8 * * 1" />
              <el-option label="每月 1 号" value="0 0 1 * *" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="showPublishDialog = false">取消</el-button>
        <el-button type="primary" :loading="publishLoading" @click="doConfirmPublish">确认发布</el-button>
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
              <el-option v-for="t in batchTableOptions" :key="t.name" :label="t.name" :value="t.name">
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
            <div style="display: flex; align-items: center; gap: 8px">
              <el-button size="small" link type="primary" @click="goToPipeline(detailModel.pipelineId)">
                关联流程 #{{ detailModel.pipelineId }}
              </el-button>
              <el-tag v-if="needsSync(detailModel)" size="small" effect="dark" type="warning" style="cursor:pointer" @click="handleSyncPipeline(detailModel)">配置未同步，点击同步</el-tag>
            </div>
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
              <div :class="['mini-flow-node', { expanded: expandedNodeId === node.id }]"
                   @click="expandedNodeId = expandedNodeId === node.id ? null : node.id">
                <div class="mini-flow-node-header">
                  <span class="mini-flow-icon">{{ pipelineNodeIcon(node.type) }}</span>
                  <span class="mini-flow-title">{{ pipelineNodeTitle(node, algorithmLabel) }}</span>
                  <span :class="['node-status-dot', isNodeConfigured(node) ? 'configured' : 'unconfigured']"></span>
                </div>
                <span class="mini-flow-detail">{{ pipelineNodeSummary(node) }}</span>
              </div>
              <!-- Expanded node params panel -->
              <div v-if="expandedNodeId === node.id" class="node-expand-panel" @click.stop>
                <NodeParamsEditor :node="node" :model="detailModel" :readonly="detailModel.status === 'training'"
                  @update="onNodeParamUpdate" />
                <div class="node-expand-actions">
                  <el-button size="small" type="primary" :loading="syncingNode" @click="doSyncNodeChanges">同步并保存</el-button>
                </div>
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
          <div v-if="overfittingWarning(detailModel)" class="overfitting-warning">
            {{ overfittingWarning(detailModel) }}
          </div>
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
            <div v-for="exec in executions" :key="exec.id" class="execution-item" :class="{ 'exec-failed-row': exec.status === EXECUTION_STATUS.FAILED }">
              <div class="exec-row-main">
                <span :class="['exec-status', 'exec-' + exec.status]">{{ execStatusLabel(exec.status) }}</span>
                <span class="exec-time">{{ exec.executionTimeMs ? (exec.executionTimeMs / 1000).toFixed(1) + 's' : '-' }}</span>
                <span class="exec-trigger">{{ execTriggerLabel(exec.triggerType) }}</span>
                <span class="exec-date">{{ formatDate(exec.createdAt) }}</span>
                <el-button v-if="exec.status === EXECUTION_STATUS.SUCCESS && detailModel" size="small" text type="warning"
                  @click="handleRollback(detailModel.id, exec.id)" style="margin-left:auto">回滚</el-button>
              </div>
              <div v-if="exec.metrics && exec.status === EXECUTION_STATUS.SUCCESS" class="exec-metrics">
                <template v-for="(val, key) in parsedMetrics(exec.metrics)" :key="key">
                  <span v-if="['accuracy','f1','precision','recall','r2','rmse','mae','silhouette_score','train_accuracy','test_accuracy','train_f1','test_f1','overfitting_gap','cv_mean','cv_std'].includes(key)" class="exec-metric-chip">{{ formatMetricName(key) }} {{ formatMetricValue(key, val) }}</span>
                </template>
              </div>
              <div v-if="exec.status === EXECUTION_STATUS.FAILED && exec.executionLog" class="exec-error">
                {{ exec.executionLog.split('\n').filter(l => l.trim()).pop() }}
              </div>
            </div>
          </div>
        </div>

        <!-- Quick actions in detail drawer -->
        <div class="detail-actions">
          <el-button size="small" type="primary" :loading="trainingId === detailModel.id"
            @click="doTrain(detailModel.id)">
            {{ detailModel.status === 'training' ? '训练中...' : '训练' }}
          </el-button>
          <el-button v-if="detailModel.status === 'trained' || detailModel.status === 'offline'" size="small" type="success"
            @click="doPublish(detailModel.id)">发布</el-button>
          <el-button v-if="detailModel.status === 'published'" size="small" type="warning"
            @click="doOffline(detailModel.id)">下线</el-button>
          <el-button v-if="detailModel.status === 'published' || detailModel.status === 'trained'" size="small" type="primary"
            @click="showDetail = false; openPredict(detailModel)">预测</el-button>
          <el-button size="small" @click="openEditModel(detailModel)">编辑</el-button>
          <el-button size="small" @click="showDetail = false; editModel(detailModel)">调参</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PipelineEditor from './PipelineEditor.vue'
import NodeParamsEditor from './NodeParamsEditor.vue'
import {
  fetchMiningModels, fetchMiningModel, createMiningModel, updateMiningModel,
  deleteMiningModel, trainMiningModel, publishMiningModel, offlineMiningModel,
  updateModelHyperparams, fetchModelExecutions, fetchDataSources,
  fetchDataSourceTables, fetchTableColumns, updateModelSchedule, predictMiningModel,
  batchPredictMiningModel, validateMiningModel, fetchModelPredictions,
  fetchMiningPipeline, previewResultTable, syncModelPipeline, rollbackModel,
  updateModelPredictConfig
} from '../api'
import { useAlgorithms } from '../composables/useAlgorithms.js'
import { useMiningStore } from '../stores/mining'
import { useUIStore } from '../stores/ui'
import { TRAINING_SAFETY_TIMEOUT_MS, DEFAULT_MODEL_TYPE, DEFAULT_ALGORITHM, PREVIEW_ROW_LIMIT, PREDICTION_RECORD_LIMIT, MODEL_STATUS, MODEL_TYPE, EXECUTION_STATUS, NODE_TYPES, NODE_TYPE_LABELS, STATUS_LABELS, SCHEDULE_INTERVALS, FILTER_VARIABLES } from '../constants'
import { useModelDetail, pipelineNodeIcon, pipelineNodeTitle, pipelineNodeSummary, isNodeConfigured, parsedMetrics, formatMetricName, formatMetricValue, metricQuality, overfittingWarning } from '../composables/useModelDetail'
import { useModelActions } from '../composables/useModelActions'

const emit = defineEmits(['close'])

const mining = useMiningStore()
const ui = useUIStore()

const {
  algorithms, modelTypes, loadAlgorithms,
  getAlgorithmLabel, getAlgorithmsForModelType,
  getAlgorithmParams, getDefaultHyperparams, getModelTypeLabel
} = useAlgorithms()

const {
  showDetail, detailModel, executions, detailPipelineNodes, expandedNodeId,
  loadingExecutions, syncingNode, sortedImportance, maxImportance,
  selectModel, onNodeParamUpdate, syncNodeChanges, refreshDetail
} = useModelDetail(mining)

const {
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
  handleValidate, cleanup: cleanupActions
} = useModelActions(mining)

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
const savingParams = ref(false)
const pipelineEditorRef = ref(null)

// Table/Column selectors (for create/edit dialog)
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
// (All dialog state moved to useModelActions composable)

function defaultForm() {
  const firstModelType = modelTypes.value.length > 0 ? modelTypes.value[0].id : DEFAULT_MODEL_TYPE
  const firstAlgo = algorithms.value.length > 0 ? algorithms.value[0].algorithmId : DEFAULT_ALGORITHM
  return {
    name: '', dataSourceId: null, sourceTable: '', modelType: firstModelType,
    algorithm: firstAlgo, featureColumnsList: [], targetColumn: '',
    description: '',
    hyperparameters: getDefaultHyperparams(firstAlgo) || {},
    preprocessing: { handleMissing: 'drop', encoding: 'label', scaling: 'none' },
    validationMode: 'train_test', cvFolds: 5, testSize: 0.2, temporalColumn: ''
  }
}

const statusLabels = STATUS_LABELS
function statusLabel(s) { return statusLabels[s] || s }

function execStatusLabel(s) {
  return { [EXECUTION_STATUS.SUCCESS]: '成功', [EXECUTION_STATUS.FAILED]: '失败', [EXECUTION_STATUS.RUNNING]: '运行中', [EXECUTION_STATUS.PENDING]: '等待中' }[s] || s
}
function execTriggerLabel(t) {
  return { manual: '手动', schedule: '定时', chat: '对话' }[t] || t
}

const algorithmLabel = getAlgorithmLabel

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
  if (key.startsWith('train_') || key.startsWith('test_') ||
      ['overfitting_gap', 'cv_mean', 'cv_std', 'confusion_matrix', 'class_labels'].includes(key)) return false
  if (modelType === MODEL_TYPE.REGRESSION) return key === 'r2'
  if (modelType === MODEL_TYPE.CLUSTERING) return key === 'silhouette_score' || key === 'inertia'
  return key === 'accuracy'
}

function primaryMetricLabel(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === MODEL_TYPE.REGRESSION) return m.r2 !== undefined ? 'R²' : 'RMSE'
  if (model.modelType === MODEL_TYPE.CLUSTERING) return '聚类'
  return m.accuracy !== undefined ? '准确率' : 'F1'
}

function primaryMetricValue(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === MODEL_TYPE.REGRESSION) return m.r2 !== undefined ? (m.r2 * 100).toFixed(1) + '%' : m.rmse?.toFixed(4) || '-'
  if (model.modelType === MODEL_TYPE.CLUSTERING) return (m.n_clusters != null ? m.n_clusters : '-') + ' 类'
  const key = m.accuracy !== undefined ? 'accuracy' : 'f1'
  return m[key] !== undefined ? (m[key] * 100).toFixed(1) + '%' : '-'
}

function primaryMetricRaw(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === 'regression') return m.r2 ?? m.rmse ?? null
  const key = m.accuracy !== undefined ? 'accuracy' : 'f1'
  return m[key] ?? null
}

function modelTypeIcon(t) {
  return { classification: '🏷️', regression: '📈', clustering: '🎯', anomaly_detection: '🔍' }[t] || '🤖'
}

function parseJson(json, fallback) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || fallback } catch { return fallback }
}
function formatDate(d) { return d ? new Date(d).toLocaleString('zh-CN') : '-' }

const cronLabelMap = {
  '*/5 * * * *': '每5分钟', '*/15 * * * *': '每15分钟', '*/30 * * * *': '每30分钟',
  '0 * * * *': '每小时', '0 6 * * *': '每天6:00', '0 8 * * *': '每天8:00',
  '0 0 * * *': '每天午夜', '0 8 * * 1': '每周一8:00', '0 0 1 * *': '每月1号',
  // Legacy single-field format (backward compat)
  '*/5': '每5分钟', '*/15': '每15分钟', '*/30': '每30分钟', '*/60': '每小时',
  '*/360': '每6小时', '*/720': '每12小时', '*/1440': '每天', '*/10080': '每周'
}
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
watch(() => mining.models, () => {
  if (detailModel.value) {
    const fresh = mining.models.find(m => m.id === detailModel.value.id)
    if (fresh) detailModel.value = fresh
  }
}, { deep: true })
watch(() => form.value.modelType, (mt) => {
  const available = getAlgorithmsForModelType(mt)
  if (available.length && !available.find(a => a.algorithmId === form.value.algorithm)) {
    form.value.algorithm = available[0].algorithmId
  }
})

// Auto-populate default hyperparams when algorithm changes during model creation
watch(() => form.value.algorithm, (algo) => {
  if (!algo) return
  const defaults = getDefaultHyperparams(algo)
  if (defaults && Object.keys(defaults).length > 0) {
    form.value.hyperparameters = defaults
  }
})
onMounted(() => {
  loadAlgorithms()
  loadModels()
  const initialId = ui.consumeMiningInitialModel()
  if (initialId) {
    nextTick(() => mining.selectModel(initialId))
  }
})

onBeforeUnmount(() => {
  mining.closeEventSource()
  cleanupActions()
})

// All action handlers (handleTrain, handlePublish, etc.) come from useModelActions composable.
// Wrapper functions for handlers that need local refs:

async function doTrain(id) { return handleTrain(id, detailModel) }
async function doPublish(id) { return handlePublish(id, models, detailModel) }
async function doConfirmPublish() { return confirmPublish(detailModel) }
async function doOffline(id) { return handleOffline(id, detailModel) }
async function doDelete(id, name) { return handleDelete(id, name, showDetail, detailModel) }
async function doSaveSchedule() { return saveSchedule(loadModels) }
async function doSyncNodeChanges() {
  try {
    await syncNodeChanges()
    ElMessage.success('节点参数已同步')
  } catch (e) {
    ElMessage.error('同步失败: ' + (e.message || ''))
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
  const ds = dataSources.value.find(d => !d.system) || dataSources.value[0]
  form.value = { ...defaultForm(), dataSourceId: ds?.id || null }
}

function openEditModel(model) {
  editingModel.value = model
  let preprocessing = { handleMissing: 'drop', encoding: 'label', scaling: 'none' }
  try { preprocessing = typeof model.preprocessing === 'string' ? JSON.parse(model.preprocessing) : model.preprocessing || preprocessing } catch {}
  let featureCols = []
  try { featureCols = typeof model.featureColumns === 'string' ? JSON.parse(model.featureColumns) : model.featureColumns || [] } catch {}

  let hyperparams = {}
  try { hyperparams = typeof model.hyperparameters === 'string' ? JSON.parse(model.hyperparameters) : model.hyperparameters || {} } catch {}

  form.value = {
    name: model.name || '',
    dataSourceId: model.dataSourceId,
    sourceTable: model.sourceTable || '',
    modelType: model.modelType || DEFAULT_MODEL_TYPE,
    algorithm: model.algorithm || DEFAULT_ALGORITHM,
    featureColumnsList: featureCols,
    targetColumn: model.targetColumn || '',
    description: model.description || '',
    hyperparameters: hyperparams,
    preprocessing: { ...preprocessing },
    validationMode: model.validationMode || 'train_test',
    cvFolds: model.cvFolds || 5,
    testSize: model.testSize || 0.2,
    temporalColumn: model.temporalColumn || ''
  }

  // Load tables and columns for this model's data source
  if (model.dataSourceId) {
    loadingTables.value = true
    fetchDataSourceTables(model.dataSourceId).then(tables => {
      tableOptions.value = tables || []
      if (model.sourceTable) {
        fetchTableColumns(model.dataSourceId, model.sourceTable).then(cols => {
          columnOptions.value = cols || []
          const checked = {}
          cols.forEach(c => { checked[c.name] = featureCols.includes(c.name) })
          featureChecked.value = checked
        }).catch(() => { columnOptions.value = [] })
      }
    }).catch(() => { tableOptions.value = [] })
    .finally(() => { loadingTables.value = false })
  }

  showDetail.value = false
  showCreateDialog.value = true
}

watch(showCreateDialog, (v) => {
  if (v && !editingModel.value) {
    const ds = dataSources.value.find(d => !d.system) || dataSources.value[0]
    form.value = { ...defaultForm(), dataSourceId: ds?.id || null }
    if (ds?.id) onDataSourceChange(ds.id)
  } else if (!v) {
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
  if (form.value.modelType !== MODEL_TYPE.CLUSTERING && !form.value.targetColumn) {
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
      hyperparameters: form.value.hyperparameters ? JSON.stringify(form.value.hyperparameters) : '{}',
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
  if (form.value.modelType !== MODEL_TYPE.CLUSTERING && !form.value.targetColumn) {
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
      hyperparameters: form.value.hyperparameters ? JSON.stringify(form.value.hyperparameters) : '{}',
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
    saving.value = false
    ElMessage.success('已创建，开始训练...')
    trainingId.value = created.id
    try {
      const trained = await trainMiningModel(created.id)
      mining.updateModelInList(trained)

      if (trained.status === 'training') {
        ElMessage.info('训练已启动，正在监听状态...')
        mining.watchModelStatus(created.id)
        await new Promise((resolve) => {
          const unwatch = watch(
            () => mining.models.find(m => m.id === created.id),
            (m) => {
              if (m && [MODEL_STATUS.TRAINED, MODEL_STATUS.FAILED, MODEL_STATUS.PUBLISHED].includes(m.status)) {
                showTrainingResult(m)
                if (detailModel.value?.id === created.id) detailModel.value = m
                unwatch()
                if (activeCleanup.unwatch === unwatch) activeCleanup.unwatch = null
                resolve()
              }
            },
            { deep: true }
          )
          activeCleanup.unwatch = unwatch
          activeCleanup.timeoutId = setTimeout(() => {
            activeCleanup.unwatch = null; activeCleanup.timeoutId = null
            unwatch(); resolve()
          }, TRAINING_SAFETY_TIMEOUT_MS)
        })
      } else {
        showTrainingResult(trained)
      }
    } catch (e) {
      ElMessage.warning('模型已创建(ID:' + created.id + ')，但训练启动失败: ' + (e.message || ''))
    }
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.message || ''))
  } finally {
    saving.value = false
    trainingId.value = null
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
  else if (cmd === 'syncPipeline') handleSyncPipeline(model)
  else if (cmd === 'delete') doDelete(model.id, model.name)
}

function truncateStr(str, max) {
  if (!str) return '-'
  return str.length > max ? str.substring(0, max) + '...' : str
}

function goToPipeline(pipelineId) {
  showDetail.value = false
  activeTab.value = 'pipeline'
  nextTick(() => {
    pipelineEditorRef.value?.openPipelineById(pipelineId)
  })
}

function goToModel(modelId) {
  activeTab.value = 'models'
  nextTick(() => {
    const model = mining.models.find(m => m.id === modelId)
    if (model) selectModel(model)
  })
}

function needsSync(model) {
  if (!model.pipelineId || !model.updatedAt) return false
  if (!model.lastSyncedAt) return true
  return new Date(model.updatedAt) > new Date(model.lastSyncedAt)
}

async function handleSyncPipeline(model) {
  try {
    await syncModelPipeline(model.id)
    ElMessage.success('模型与流程已同步')
    await loadModels()
  } catch (e) {
    ElMessage.error('同步失败: ' + (e.message || '未知错误'))
  }
}

async function handleRollback(modelId, executionId) {
  try {
    await rollbackModel(modelId, executionId)
    ElMessage.success(`已回滚到执行记录 #${executionId}`)
    await loadModels()
    if (detailModel.value?.id === modelId) {
      detailModel.value = await fetchMiningModel(modelId)
      executions.value = await fetchModelExecutions(modelId) || []
    }
  } catch (e) {
    ElMessage.error('回滚失败: ' + (e.message || '未知错误'))
  }
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
.status-failed { background: var(--color-danger-light); color: var(--color-danger); }
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
.overfitting-warning { padding: 8px 12px; border-radius: 6px; margin-bottom: 8px; font-size: 13px; background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); border: 1px solid var(--el-color-warning-light-7); }
.detail-section h4 {
  font-size: var(--font-base); font-weight: 600; color: var(--text-primary);
  margin-bottom: var(--space-sm); padding-bottom: var(--space-xs);
  border-bottom: 1px solid var(--border-light);
}
.feature-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.pipeline-mini-flow {
  display: flex; align-items: flex-start; gap: 2px; overflow-x: auto;
  padding: 8px 0; flex-wrap: wrap;
}
.mini-flow-node {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  background: var(--bg-secondary); border: 1px solid var(--border-light);
  border-radius: 6px; padding: 6px 8px; min-width: 60px; max-width: 80px;
  text-align: center; flex-shrink: 0; cursor: pointer; transition: all 0.15s;
}
.mini-flow-node:hover { border-color: var(--primary); background: var(--primary-light); }
.mini-flow-node.expanded { border-color: var(--primary); background: var(--primary-light); box-shadow: 0 0 0 2px var(--primary-light); }
.mini-flow-node-header { display: flex; align-items: center; gap: 2px; }
.mini-flow-icon { font-size: 16px; }
.mini-flow-title { font-size: 10px; font-weight: 600; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 70px; }
.mini-flow-detail { font-size: 9px; color: var(--text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 70px; }
.node-status-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.node-status-dot.configured { background: var(--el-color-success); }
.node-status-dot.unconfigured { background: var(--el-color-danger); }
.mini-flow-arrow { color: var(--text-muted); font-size: 12px; margin-top: 14px; flex-shrink: 0; }
.node-expand-panel {
  width: 100%; margin: 4px 0; padding: 12px; background: var(--bg-secondary);
  border: 1px solid var(--border); border-radius: 8px; order: 999;
  animation: slideDown 0.2s ease;
}
.node-expand-actions { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--border); text-align: right; }
@keyframes slideDown { from { opacity: 0; transform: translateY(-8px); } to { opacity: 1; transform: translateY(0); } }
.var-helper { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; margin-top: 4px; }
.var-label { font-size: 12px; color: var(--text-muted); }
.var-chip { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--primary-light); color: var(--primary); cursor: pointer; font-family: monospace; }
.var-chip:hover { background: var(--primary); color: #fff; }
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
