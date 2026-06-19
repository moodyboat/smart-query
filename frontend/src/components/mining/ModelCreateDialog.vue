<template>
  <el-dialog :model-value="show" @update:model-value="$emit('update:show', $event)"
    :title="editing ? '编辑模型' : '新建模型'" width="660px" destroy-on-close>
    <el-form :model="form" label-width="100px" size="default">
      <el-form-item label="模型名称" required>
        <el-input :model-value="form.name" @update:model-value="updateForm('name', $event)" placeholder="如: 员工离职预测" />
      </el-form-item>
      <el-form-item label="数据源" required>
        <el-select :model-value="form.dataSourceId" @update:model-value="updateForm('dataSourceId', $event)" placeholder="选择数据源" style="width: 100%"
          :teleported="false" @change="$emit('dataSourceChange', $event)">
          <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="源数据表" required>
        <el-select :model-value="form.sourceTable" @update:model-value="updateForm('sourceTable', $event)" placeholder="选择数据表" style="width: 100%"
          :teleported="false" :loading="loadingTables" :disabled="!form.dataSourceId"
          @change="$emit('tableChange', $event)" filterable>
          <el-option v-for="t in tableOptions" :key="t.name" :label="t.comment ? `${t.name} (${t.comment})` : t.name" :value="t.name">
            <span>{{ t.name }}</span>
            <span v-if="t.comment" style="color: var(--text-muted); margin-left: 8px; font-size: var(--font-sm)">{{ t.comment }}</span>
            <span style="float: right; color: var(--text-muted); font-size: var(--font-sm)">{{ t.rows }}行</span>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="模型类型" required>
        <el-select :model-value="form.modelType" @update:model-value="updateForm('modelType', $event)" placeholder="选择类型" style="width: 100%" :teleported="false">
          <el-option v-for="mt in modelTypes" :key="mt.id" :label="`${mt.name}`" :value="mt.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="算法" required>
        <el-select :model-value="form.algorithm" @update:model-value="updateForm('algorithm', $event)" placeholder="选择算法" style="width: 100%" :teleported="false">
          <el-option v-for="a in filteredAlgorithms" :key="a.algorithmId" :label="`${a.icon || ''} ${a.name}`" :value="a.algorithmId" />
        </el-select>
      </el-form-item>
      <el-form-item label="特征列" required>
        <div v-if="!columnOptions.length" class="col-hint">
          请先选择数据源和数据表
        </div>
        <div v-else class="column-picker">
          <el-checkbox :model-value="selectAllFeatures" :indeterminate="featureIndeterminate"
            @change="$emit('selectAllFeatures', $event)" style="margin-bottom: 6px">全选</el-checkbox>
          <div class="column-grid">
            <el-checkbox v-for="col in columnOptions" :key="col.name"
              :model-value="featureChecked[col.name]" @change="val => $emit('toggleFeature', col.name, val)">
              <span class="col-name">{{ col.name }}</span>
              <span class="col-type">{{ col.type }}</span>
            </el-checkbox>
          </div>
          <div class="selected-count">已选 {{ form.featureColumnsList.length }} / {{ columnOptions.length }} 列</div>
        </div>
      </el-form-item>
      <el-form-item v-if="form.modelType !== MODEL_TYPE.CLUSTERING" label="目标列">
        <el-select :model-value="form.targetColumn" @update:model-value="updateForm('targetColumn', $event)" placeholder="选择目标列" style="width: 100%"
          :teleported="false" :disabled="!columnOptions.length" clearable filterable>
          <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name">
            <span>{{ col.name }}</span>
            <span style="color: var(--text-muted); margin-left: 8px; font-size: var(--font-sm)">{{ col.type }}</span>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="缺失值处理">
        <el-select :model-value="form.preprocessing?.handleMissing" @update:model-value="updatePreprocessing('handleMissing', $event)" style="width: 100%" :teleported="false">
          <el-option label="删除缺失行" value="drop" />
          <el-option label="填充均值" value="fill_mean" />
          <el-option label="填充中位数" value="fill_median" />
        </el-select>
      </el-form-item>
      <el-form-item label="分类编码">
        <el-select :model-value="form.preprocessing?.encoding" @update:model-value="updatePreprocessing('encoding', $event)" style="width: 100%" :teleported="false">
          <el-option label="Label Encoding" value="label" />
          <el-option label="One-Hot Encoding" value="onehot" />
        </el-select>
      </el-form-item>
      <el-form-item label="特征缩放">
        <el-select :model-value="form.preprocessing?.scaling" @update:model-value="updatePreprocessing('scaling', $event)" style="width: 100%" :teleported="false">
          <el-option label="不缩放" value="none" />
          <el-option label="标准化 (StandardScaler)" value="standard" />
          <el-option label="归一化 (MinMaxScaler)" value="minmax" />
        </el-select>
      </el-form-item>
      <el-form-item label="描述">
        <el-input :model-value="form.description" @update:model-value="updateForm('description', $event)" type="textarea" :rows="2" placeholder="模型用途描述" />
      </el-form-item>
      <el-form-item label="验证模式">
        <el-select :model-value="form.validationMode" @update:model-value="updateForm('validationMode', $event)" style="width: 100%" :teleported="false">
          <el-option label="训练/测试分割 (默认)" value="train_test" />
          <el-option label="交叉验证 (K-Fold)" value="cv" />
          <el-option label="样本外验证 (OOS)" value="oos" />
          <el-option label="时间外验证 (Temporal)" value="temporal" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.validationMode === 'cv' || form.validationMode === 'oos'" label="CV折数">
        <el-input-number :model-value="form.cvFolds" @update:model-value="updateForm('cvFolds', $event)" :min="2" :max="20" :step="1" />
      </el-form-item>
      <el-form-item v-if="form.validationMode === 'temporal'" label="时间列">
        <el-select :model-value="form.temporalColumn" @update:model-value="updateForm('temporalColumn', $event)" placeholder="选择时间列" style="width: 100%" :teleported="false" filterable>
          <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:show', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="$emit('save')">
        {{ editing ? '保存' : '创建' }}
      </el-button>
      <el-button v-if="!editing" type="success" :loading="saving" @click="$emit('saveAndTrain')">
        创建并训练
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
.col-hint { color: var(--text-muted); font-size: var(--font-sm); padding: 8px 0; }
.column-picker { width: 100%; }
.column-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px 12px;
  max-height: 200px; overflow-y: auto; border: 1px solid var(--border-light);
  border-radius: var(--radius-md); padding: var(--space-sm);
}
.col-name { font-size: var(--font-sm); }
.col-type { font-size: var(--font-xs); color: var(--text-muted); margin-left: 4px; }
.selected-count { font-size: var(--font-sm); color: var(--text-muted); margin-top: 4px; }
</style>
