package com.smartquery.engine;

import com.smartquery.coordinator.TaskCoordinator;
import com.smartquery.coordinator.model.Task;
import com.smartquery.coordinator.model.TaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务协调集成器
 *
 * <p>负责在 ReAct 引擎中集成任务协调功能
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoordinatorIntegration {

    private final TaskCoordinator taskCoordinator;

    /**
     * 检查用户消息是否需要任务协调
     *
     * <p>已停用：真实的多算法对比由 {@code MiningModelTool} 的 {@code compare} action 实现——
     * 它要求 source_table + target_column，调用 {@code MiningService.trainModel} 真实训练并产出真实指标。
     * 此前本协调器路径仅从自然语言提取算法名（无数据源/目标列上下文），返回 {@code Math.random()} 假数据
     * 并注入 LLM 上下文（见 {@link com.smartquery.coordinator.executor.ModelTaskExecutor}），既造假又可能与
     * 真实工具竞争。停用后让 LLM 走 {@code mining_model.compare} 真实路径。
     * 相关死代码（extractTasks / extractModelComparisonTasks / ModelTaskExecutor）待后续清理或真实重写。
     *
     * @param userMessage 用户消息
     * @return 恒为 false（协调器对比路径已停用）
     */
    public boolean needsCoordination(String userMessage) {
        return false;
    }

    /**
     * 从用户消息中提取需要协调的任务
     *
     * @param userMessage 用户消息
     * @return 提取的任务列表
     */
    public List<Task> extractTasks(String userMessage) {
        List<Task> tasks = new ArrayList<>();

        // 示例：检测模型对比任务
        // "对比随机森林和XGBoost"
        if (userMessage.contains("对比") && userMessage.contains("模型")) {
            tasks.addAll(extractModelComparisonTasks(userMessage));
        }

        // TODO: 添加更多任务类型提取逻辑

        return tasks;
    }

    /**
     * 提取模型对比任务
     */
    private List<Task> extractModelComparisonTasks(String userMessage) {
        List<Task> tasks = new ArrayList<>();

        log.info("[COORDINATOR-INTEGRATION] Extracting model comparison tasks from: {}", userMessage);

        // 更灵活的算法名称提取
        // 匹配 "对比X和Y"、"比较X与Y" 等模式
        Pattern pattern = Pattern.compile("(?:对比|比较)(.+?)(?:和|与|以及|以及)(.+?)(?:模型|算法|两个模型|两个算法)");
        Matcher matcher = pattern.matcher(userMessage);

        if (matcher.find()) {
            String alg1 = matcher.group(1).trim();
            String alg2 = matcher.group(2).trim();

            // 清理算法名称中的额外词语
            alg1 = alg1.replaceAll("模型|算法", "").trim();
            alg2 = alg2.replaceAll("模型|算法", "").trim();

            log.info("[COORDINATOR-INTEGRATION] Extracted algorithms: '{}' and '{}'", alg1, alg2);

            String taskId1 = "train_" + alg1.replaceAll("[\\s]+", "_");
            String taskId2 = "train_" + alg2.replaceAll("[\\s]+", "_");

            // 创建两个训练任务
            tasks.add(Task.builder()
                .taskId(taskId1)
                .taskType("model_training")
                .description("训练" + alg1 + "模型")
                .parameters(Map.of(
                    "algorithm", alg1,
                    "action", "train"
                ))
                .dependencies(List.of())
                .build());

            tasks.add(Task.builder()
                .taskId(taskId2)
                .taskType("model_training")
                .description("训练" + alg2 + "模型")
                .parameters(Map.of(
                    "algorithm", alg2,
                    "action", "train"
                ))
                .dependencies(List.of())
                .build());

            // 创建对比任务（依赖两个训练任务）
            tasks.add(Task.builder()
                .taskId("compare_results")
                .taskType("model_comparison")
                .description("对比模型结果")
                .parameters(Map.of(
                    "algorithms", List.of(alg1, alg2),
                    "modelIds", List.of(taskId1, taskId2)
                ))
                .dependencies(List.of(taskId1, taskId2))
                .build());
        } else {
            log.warn("[COORDINATOR-INTEGRATION] Could not extract algorithms from message: {}", userMessage);
        }

        return tasks;
    }

    /**
     * 执行任务协调
     *
     * @param mainTask 主任务
     * @param subTasks 子任务列表
     * @return 协调结果
     */
    public List<TaskResult> coordinate(String mainTask, List<Task> subTasks) {
        log.info("[COORDINATOR-INTEGRATION] Coordinating {} subtasks for main task: {}",
                 subTasks.size(), mainTask);

        return taskCoordinator.coordinate(mainTask, subTasks);
    }

    /**
     * 将协调结果格式化为可读文本
     *
     * @param results 任务结果列表
     * @return 格式化的文本
     */
    public String formatResults(List<TaskResult> results) {
        if (results == null || results.isEmpty()) {
            return "任务执行完成，无结果返回。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 任务执行结果\n\n");

        for (TaskResult result : results) {
            sb.append("**任务**: ").append(result.getTaskId()).append("\n");
            sb.append("- 状态: ").append(result.isSuccess() ? "成功" : "失败").append("\n");

            if (result.isSuccess() && result.getData() != null) {
                Object data = result.getData();
                if (data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dataMap = (Map<String, Object>) data;
                    if (dataMap.containsKey("table")) {
                        sb.append("\n").append(dataMap.get("table")).append("\n");
                    } else {
                        sb.append("- 结果: ").append(data).append("\n");
                    }
                } else {
                    sb.append("- 结果: ").append(data).append("\n");
                }
            } else if (!result.isSuccess()) {
                sb.append("- 错误: ").append(result.getError()).append("\n");
            }

            sb.append("- 耗时: ").append(result.getExecutionTimeMs()).append("ms\n");
            sb.append("\n");
        }

        return sb.toString();
    }
}
