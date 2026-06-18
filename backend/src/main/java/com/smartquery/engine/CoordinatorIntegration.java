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
     * @param userMessage 用户消息
     * @return 是否需要协调
     */
    public boolean needsCoordination(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) {
            return false;
        }

        // 检测关键词和模式
        String[] coordinationKeywords = {
            "对比.*模型", "比较.*模型", "对比.*算法", "比较.*算法",
            "分别.*训练", "同时.*训练", "并行.*训练",
            "对比.*和.*", "比较.*和.*", "与.*对比", "与.*比较"
        };

        for (String keyword : coordinationKeywords) {
            if (Pattern.compile(keyword).matcher(userMessage).find()) {
                log.info("[COORDINATOR-INTEGRATION] Detected coordination keyword: {} in message: {}",
                         keyword, userMessage);
                return true;
            }
        }

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
