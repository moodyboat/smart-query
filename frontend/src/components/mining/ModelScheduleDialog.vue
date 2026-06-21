<template>
  <el-dialog :model-value="show" @update:model-value="$emit('update:show', $event)"
    title="定时调度" width="460px" destroy-on-close>
    <div v-if="model">
      <p style="margin-bottom: 12px; color: var(--text-secondary)">为「{{ model.name }}」配置定时调度</p>
      <el-form label-width="100px" size="default">
        <el-form-item label="启用调度">
          <el-switch :model-value="enabled" @update:model-value="$emit('update:enabled', $event)" active-text="开" inactive-text="关" />
        </el-form-item>
        <el-form-item label="调度模式">
          <el-radio-group :model-value="mode" @update:model-value="$emit('update:mode', $event)">
            <el-radio value="train">定期重训</el-radio>
            <el-radio value="predict">定期预测</el-radio>
          </el-radio-group>
          <div style="font-size: var(--font-sm); color: var(--text-muted); margin-top: 4px">
            {{ mode === 'predict' ? '用已发布模型对新数据表批量预测，结果写入结果表' : '用最新数据重新训练模型' }}
          </div>
        </el-form-item>
        <el-form-item v-if="mode === 'predict'" label="输入表">
          <el-select :model-value="inputTable" @update:model-value="$emit('update:inputTable', $event)" placeholder="选择预测输入表" style="width: 100%" :teleported="false" filterable>
            <el-option v-for="t in tableOptions" :key="t.name" :label="t.name" :value="t.name" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="mode === 'predict'" label="结果表">
          <el-input :model-value="resultTable" @update:model-value="$emit('update:resultTable', $event)" placeholder="预测结果保存表名" />
        </el-form-item>
        <el-form-item label="调度间隔">
          <el-select :model-value="cron" @update:model-value="$emit('update:cron', $event)" style="width: 100%" :teleported="false">
            <el-option label="每 30 分钟" value="*/30 * * * *" />
            <el-option label="每 1 小时" value="0 * * * *" />
            <el-option label="每天早上 6:00" value="0 6 * * *" />
            <el-option label="每天早上 8:00" value="0 8 * * *" />
            <el-option label="每天午夜" value="0 0 * * *" />
            <el-option label="每周一 8:00" value="0 8 * * 1" />
            <el-option label="每月 1 号" value="0 0 1 * *" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="mode === 'predict'" label="筛选条件">
          <el-input :model-value="inputFilter" @update:model-value="$emit('update:inputFilter', $event)" placeholder="如: etl_date &lt;= '${etl_date}' 或 status = 'active'" />
          <div class="var-helper">
            <span class="var-label">可用变量:</span>
            <code class="var-chip" @click="appendVar('${etl_date}')">${etl_date}</code>
            <code class="var-chip" @click="appendVar('${today}')">${today}</code>
            <code class="var-chip" @click="appendVar('${yesterday}')">${yesterday}</code>
            <code class="var-chip" @click="appendVar('${today-N}')">${today-N}</code>
          </div>
        </el-form-item>
      </el-form>
      <div v-if="model.lastRunAt" style="margin-top: 8px; font-size: var(--font-sm); color: var(--text-muted)">
        上次运行: {{ new Date(model.lastRunAt).toLocaleString('zh-CN') }}
      </div>
    </div>
    <template #footer>
      <el-button @click="$emit('update:show', false)">取消</el-button>
      <el-button type="primary" @click="$emit('save')">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
const props = defineProps({
  show: { type: Boolean, default: false },
  model: { type: Object, default: null },
  enabled: { type: Boolean, default: false },
  mode: { type: String, default: 'train' },
  cron: { type: String, default: '0 6 * * *' },
  inputTable: { type: String, default: '' },
  resultTable: { type: String, default: '' },
  inputFilter: { type: String, default: '' },
  tableOptions: { type: Array, default: () => [] }
})

const emit = defineEmits([
  'update:show', 'update:enabled', 'update:mode', 'update:cron',
  'update:inputTable', 'update:resultTable', 'update:inputFilter', 'save'
])

function appendVar(variable) {
  emit('update:inputFilter', (props.inputFilter || '') + variable)
}
</script>

<style scoped>
.var-helper { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; margin-top: 4px; }
.var-label { font-size: var(--font-sm); color: var(--text-muted); }
.var-chip { font-size: var(--font-xs); padding: 1px 6px; border-radius: var(--radius-sm); background: var(--primary-light); color: var(--primary); cursor: pointer; font-family: var(--font-family-mono); }
.var-chip:hover { background: var(--primary); color: #fff; }
</style>
