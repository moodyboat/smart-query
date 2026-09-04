<template>
  <el-dialog :model-value="show" @update:model-value="$emit('update:show', $event)"
    :title="editing ? '调整机器学习算子' : '新建机器学习算子'" width="min(760px, 94vw)" destroy-on-close>
    <div class="builder-steps"><span><em>1</em>基本信息</span><span><em>2</em>选择训练数据</span><span><em>3</em>平台自动训练</span></div>
    <el-form :model="form" label-position="top" size="default" class="simple-model-form">
      <section class="setup-section">
        <header><em>1</em><div><strong>基本信息</strong><span>说明这个算子要解决什么问题</span></div></header>
        <div class="form-grid">
          <el-form-item label="算子名称" required>
            <el-input :model-value="form.name" @update:model-value="updateForm('name', $event)" placeholder="例如：员工离职预测" />
          </el-form-item>
          <el-form-item label="分析目标" required>
            <el-select :model-value="form.modelType" @update:model-value="updateForm('modelType', $event)" placeholder="选择要完成的任务" style="width:100%" :teleported="false">
              <el-option v-for="mt in modelTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="用途说明">
          <el-input :model-value="form.description" @update:model-value="updateForm('description', $event)" type="textarea" :rows="2" placeholder="简要说明使用场景" />
        </el-form-item>
      </section>

      <section class="setup-section">
        <header><em>2</em><div><strong>选择训练数据</strong><span>选择数据表，并告诉平台需要分析哪些字段</span></div></header>
        <div class="form-grid">
          <el-form-item label="数据源" required>
            <el-select :model-value="form.dataSourceId" @update:model-value="updateForm('dataSourceId', $event)" placeholder="选择数据源" style="width:100%"
              :teleported="false" @change="$emit('dataSourceChange', $event)">
              <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="数据表" required>
            <el-select :model-value="form.sourceTable" @update:model-value="updateForm('sourceTable', $event)" placeholder="选择数据表" style="width:100%"
              :teleported="false" :loading="loadingTables" :disabled="!form.dataSourceId" @change="$emit('tableChange', $event)" filterable>
              <el-option v-for="t in tableOptions" :key="t.name" :label="t.comment ? `${t.comment}（${t.name}）` : t.name" :value="t.name">
                <span>{{ t.comment || t.name }}</span><span style="float:right;color:var(--text-muted);font-size:11px">{{ t.rows }} 行</span>
              </el-option>
            </el-select>
          </el-form-item>
        </div>
        <el-form-item v-if="form.modelType !== MODEL_TYPE.CLUSTERING" label="要预测的字段" required>
          <el-select :model-value="form.targetColumn" @update:model-value="updateForm('targetColumn', $event)" placeholder="选择结果字段" style="width:100%"
            :teleported="false" :disabled="!columnOptions.length" clearable filterable>
            <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="用于分析的字段" required>
          <div v-if="!columnOptions.length" class="col-hint">选择数据源和数据表后即可勾选字段</div>
          <div v-else class="column-picker">
            <div class="column-picker-head"><el-checkbox :model-value="selectAllFeatures" :indeterminate="featureIndeterminate" @change="$emit('selectAllFeatures', $event)">全选</el-checkbox><span>已选 {{ form.featureColumnsList.length }} 项</span></div>
            <div class="column-grid">
              <el-checkbox v-for="col in columnOptions" :key="col.name" :model-value="featureChecked[col.name]" @change="val => $emit('toggleFeature', col.name, val)">
                <span class="col-name">{{ col.name }}</span>
              </el-checkbox>
            </div>
          </div>
        </el-form-item>
      </section>

      <el-collapse class="advanced-settings">
        <el-collapse-item title="高级设置（已使用平台推荐配置）" name="advanced">
          <el-form-item label="训练方法">
            <el-select :model-value="form.algorithm" @update:model-value="updateForm('algorithm', $event)" placeholder="选择训练方法" style="width:100%" :teleported="false">
              <el-option v-for="a in filteredAlgorithms" :key="a.algorithmId" :label="a.name" :value="a.algorithmId" />
            </el-select>
          </el-form-item>
          <div class="form-grid three">
            <el-form-item label="缺失数据"><el-select :model-value="form.preprocessing?.handleMissing" @update:model-value="updatePreprocessing('handleMissing', $event)" style="width:100%" :teleported="false"><el-option label="删除不完整记录" value="drop" /><el-option label="按平均值补全" value="fill_mean" /><el-option label="按中位数补全" value="fill_median" /></el-select></el-form-item>
            <el-form-item label="分类字段"><el-select :model-value="form.preprocessing?.encoding" @update:model-value="updatePreprocessing('encoding', $event)" style="width:100%" :teleported="false"><el-option label="自动编号" value="label" /><el-option label="拆分为独立选项" value="onehot" /></el-select></el-form-item>
            <el-form-item label="数值处理"><el-select :model-value="form.preprocessing?.scaling" @update:model-value="updatePreprocessing('scaling', $event)" style="width:100%" :teleported="false"><el-option label="保持原值" value="none" /><el-option label="标准化" value="standard" /><el-option label="归一化" value="minmax" /></el-select></el-form-item>
          </div>
          <el-form-item label="结果检查方式"><el-select :model-value="form.validationMode" @update:model-value="updateForm('validationMode', $event)" style="width:100%" :teleported="false"><el-option label="训练集与测试集（推荐）" value="train_test" /><el-option label="分组交叉检查" value="cv" /><el-option label="独立样本检查" value="oos" /><el-option label="按时间区间检查" value="temporal" /></el-select></el-form-item>
          <el-form-item v-if="form.validationMode === 'cv' || form.validationMode === 'oos'" label="检查分组数"><el-input-number :model-value="form.cvFolds" @update:model-value="updateForm('cvFolds', $event)" :min="2" :max="20" :step="1" /></el-form-item>
          <el-form-item v-if="form.validationMode === 'temporal'" label="时间字段"><el-select :model-value="form.temporalColumn" @update:model-value="updateForm('temporalColumn', $event)" placeholder="选择时间字段" style="width:100%" :teleported="false" filterable><el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" /></el-select></el-form-item>
        </el-collapse-item>
      </el-collapse>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:show', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="$emit('save')">
        {{ editing ? '保存设置' : '保存草稿' }}
      </el-button>
      <el-button v-if="!editing" type="success" :loading="saving" @click="$emit('saveAndTrain')">
        保存并开始训练
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { MODEL_TYPE } from '../../constants'

const props = defineProps({
  show: { type: Boolean, default: false },
  editing: { type: Boolean, default: false },
  form: { type: Object, required: true },
  saving: { type: Boolean, default: false },
  dataSources: { type: Array, default: () => [] },
  modelTypes: { type: Array, default: () => [] },
  filteredAlgorithms: { type: Array, default: () => [] },
  columnOptions: { type: Array, default: () => [] },
  loadingTables: { type: Boolean, default: false },
  tableOptions: { type: Array, default: () => [] },
  featureChecked: { type: Object, default: () => ({}) },
  selectAllFeatures: { type: Boolean, default: false },
  featureIndeterminate: { type: Boolean, default: false },
  MODEL_TYPE: { type: Object, default: () => MODEL_TYPE }
})

const emit = defineEmits([
  'update:show', 'update:form', 'save', 'saveAndTrain',
  'dataSourceChange', 'tableChange', 'selectAllFeatures',
  'toggleFeature', 'syncFeatureColumns'
])

function updateForm(field, value) {
  emit('update:form', { ...props.form, [field]: value })
}

function updatePreprocessing(field, value) {
  emit('update:form', {
    ...props.form,
    preprocessing: { ...(props.form.preprocessing || {}), [field]: value }
  })
}
</script>

<style scoped>
.builder-steps { display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:-4px 0 14px; }
.builder-steps span { display:flex;align-items:center;gap:7px;padding:8px 10px;border-radius:8px;color:#586579;background:#f5f7fa;font-size:11px; }
.builder-steps em,.setup-section header>em { width:23px;height:23px;display:grid;place-items:center;flex:none;border-radius:6px;color:#2468f2;background:#edf3ff;font-size:10px;font-style:normal;font-weight:700; }
.simple-model-form { display:flex;flex-direction:column;gap:12px; }
.setup-section { padding:14px 15px 2px;border:1px solid #e4e8ef;border-radius:9px;background:#fff; }
.setup-section header { display:flex;align-items:center;gap:9px;margin-bottom:13px; }
.setup-section header>div { display:flex;flex-direction:column;gap:2px; }
.setup-section header strong { color:#344054;font-size:13px; }
.setup-section header span { color:#86909c;font-size:10px; }
.form-grid { display:grid;grid-template-columns:1fr 1fr;gap:10px; }
.form-grid.three { grid-template-columns:repeat(3,1fr); }
.simple-model-form :deep(.el-form-item) { margin-bottom:12px; }
.advanced-settings { padding:0 12px;border:1px solid #e4e8ef;border-radius:9px;background:#fafbfc; }
.advanced-settings :deep(.el-collapse-item__header) { height:44px;color:#586579;background:transparent;font-size:12px; }
.advanced-settings :deep(.el-collapse-item__wrap),.advanced-settings :deep(.el-collapse-item__content) { background:transparent; }
.col-hint { width:100%;padding:12px;border:1px dashed #d9dee7;border-radius:8px;color:#86909c;background:#fafbfc;font-size:11px;text-align:center; }
.column-picker { width: 100%; }
.column-picker-head { display:flex;align-items:center;justify-content:space-between;margin-bottom:7px;color:#86909c;font-size:10px; }
.column-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px 12px;
  max-height: 180px; overflow-y: auto; border: 1px solid #e4e8ef;
  border-radius: 8px; padding: 10px; background:#fafbfc;
}
.col-name { font-size: var(--font-sm); }
@media (max-width:680px) { .builder-steps,.form-grid,.form-grid.three{grid-template-columns:1fr}.column-grid{grid-template-columns:repeat(2,1fr)} }
</style>
