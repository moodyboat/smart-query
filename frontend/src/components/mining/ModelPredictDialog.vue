<template>
  <!-- Single Predict Dialog -->
  <el-dialog :model-value="showPredict" @update:model-value="$emit('update:showPredict', $event)"
    title="模型预测" width="660px" destroy-on-close>
    <div v-if="predictModel">
      <p style="color: var(--text-secondary); margin-bottom: 12px">
        使用「{{ predictModel.name }}」进行预测，输入数据格式为 JSON 数组
      </p>
      <el-form label-width="100px" size="default">
        <el-form-item label="输入数据">
          <el-input :model-value="predictInput" @update:model-value="$emit('update:predictInput', $event)" type="textarea" :rows="6" placeholder='[{"dept_id": 1, "salary": 15000}]' />
        </el-form-item>
        <el-form-item label="保存到表">
          <el-input :model-value="predictSaveTable" @update:model-value="$emit('update:predictSaveTable', $event)" placeholder="留空不保存，填写表名则自动建表并写入预测结果" />
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
      <el-button @click="$emit('update:showPredict', false)">关闭</el-button>
      <el-button type="primary" :loading="predictLoading" @click="$emit('predict')">执行预测</el-button>
    </template>
  </el-dialog>

  <!-- Batch Predict Dialog -->
  <el-dialog :model-value="showBatch" @update:model-value="$emit('update:showBatch', $event)"
    title="批量预测" width="560px" destroy-on-close>
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
          <el-select :model-value="batchInputTable" @update:model-value="$emit('update:batchInputTable', $event)" placeholder="选择要预测的数据表" style="width: 100%" :teleported="false" filterable>
            <el-option v-for="t in batchTableOptions" :key="t.name" :label="t.name" :value="t.name">
              <span>{{ t.name }}</span>
              <span style="float: right; color: var(--text-muted); font-size: var(--font-sm)">{{ t.rows }}行</span>
            </el-option>
          </el-select>
          <div style="font-size: var(--font-sm); color: var(--text-muted); margin-top: 4px">该表必须包含模型训练时的特征列</div>
        </el-form-item>
        <el-form-item label="结果表">
          <el-input :model-value="batchResultTable" @update:model-value="$emit('update:batchResultTable', $event)" placeholder="留空则自动生成表名" />
          <div style="font-size: var(--font-sm); color: var(--text-muted); margin-top: 4px">
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
        <div style="font-size: var(--font-md); color: var(--text-secondary)">
          结果表列: {{ (batchPredictResult.columns || []).join(', ') }}
        </div>
        <el-button size="small" type="primary" plain style="margin-top: 8px"
          @click="$emit('previewResult', batchPredictResult.saved_to)">
          预览结果数据
        </el-button>
        <div v-if="resultPreview.length" style="margin-top: 8px">
          <el-table :data="resultPreview" size="small" stripe border max-height="200">
            <el-table-column type="index" label="#" width="40" />
            <el-table-column v-for="col in resultPreviewColumns" :key="col" :prop="col" :label="col" show-overflow-tooltip />
          </el-table>
          <div style="font-size: var(--font-xs); color: var(--text-muted); margin-top: 4px">仅展示前10行</div>
        </div>
      </div>
    </div>
    <template #footer>
      <el-button @click="$emit('update:showBatch', false)">关闭</el-button>
      <el-button type="primary" :loading="batchPredictLoading" @click="$emit('batchPredict')">执行批量预测</el-button>
    </template>
  </el-dialog>

  <!-- Prediction Results Dialog -->
  <el-dialog :model-value="showResults" @update:model-value="$emit('update:showResults', $event)"
    title="预测记录" width="700px" destroy-on-close>
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
            <span style="font-size: var(--font-sm)">{{ truncateStr(row.inputData, 80) }}</span>
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
</template>

<script setup>
defineProps({
  showPredict: { type: Boolean, default: false },
  showBatch: { type: Boolean, default: false },
  showResults: { type: Boolean, default: false },
  predictModel: { type: Object, default: null },
  batchPredictModel: { type: Object, default: null },
  predictResultsModel: { type: Object, default: null },
  predictInput: { type: String, default: '' },
  predictSaveTable: { type: String, default: '' },
  predictResult: { type: Object, default: null },
  predictRows: { type: Array, default: () => [] },
  predictLoading: { type: Boolean, default: false },
  batchInputTable: { type: String, default: '' },
  batchResultTable: { type: String, default: '' },
  batchPredictResult: { type: Object, default: null },
  batchPredictLoading: { type: Boolean, default: false },
  resultPreview: { type: Array, default: () => [] },
  resultPreviewColumns: { type: Array, default: () => [] },
  loadingPredictions: { type: Boolean, default: false },
  predictionResults: { type: Array, default: () => [] },
  batchTableOptions: { type: Array, default: () => [] },
  algorithmLabel: { type: Function, required: true },
  formatDate: { type: Function, required: true },
  parseJson: { type: Function, required: true }
})

defineEmits([
  'update:showPredict', 'update:showBatch', 'update:showResults',
  'update:predictInput', 'update:predictSaveTable',
  'update:batchInputTable', 'update:batchResultTable',
  'predict', 'batchPredict', 'previewResult'
])

function truncateStr(str, max) {
  if (!str) return '-'
  return str.length > max ? str.substring(0, max) + '...' : str
}
</script>

<style scoped>
.predict-result { margin-top: 16px; }
.predict-result h4 { font-size: var(--font-md); color: var(--text-primary); margin-bottom: 8px; }
</style>
