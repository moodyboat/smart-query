import { MODEL_STATUS } from '../constants'

/**
 * 判断是否为"幽灵模型"（开发遗留判断：状态异常或 modelPath 指向开发机本地）。
 *
 * TODO: 当前基于开发者本机路径（/Users/gonghang、C:\Users\lenovo、/tmp 工作区），
 * 换机器/部署环境不可靠。应由后端提供模型文件存在性接口替换此处的字符串猜测。
 *
 * 抽取到此处的目的：消除 useModelActions.js 与 MiningManager.vue 的重复（DRY），
 * 并集中硬编码路径便于未来统一重构。
 */
export function isGhostModel(model) {
  if (!model) return false
  return model.status === MODEL_STATUS.FAILED ||
         (model.modelPath && !model.modelPath.includes('C:\\Users\\lenovo')) ||
         (model.modelPath && model.modelPath.includes('/Users/gonghang')) ||
         (model.modelPath && model.modelPath.includes('/tmp/smartquery-workspace'))
}
