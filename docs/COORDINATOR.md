# 任务协调器使用指南

## 功能概述

任务协调器（Task Coordinator）是智能问数系统的新功能，用于协调执行复杂的并行任务。

## 主要特性

1. **并行执行**: 自动识别可并行的任务，提高执行效率
2. **依赖管理**: 按任务依赖关系（DAG）执行，确保依赖任务先完成
3. **错误处理**: 单个任务失败不影响其他任务
4. **结果汇总**: 自动汇总所有子任务的结果

## 使用场景

### 1. 模型对比

**用户输入**: "对比随机森林和XGBoost两个模型"

**系统行为**:
1. 自动识别为模型对比任务
2. 分解为3个子任务:
   - 训练随机森林模型
   - 训练XGBoost模型（并行执行）
   - 对比两个模型的结果（依赖前两个任务）
3. 返回对比表格

### 2. 并行数据分析

**用户输入**: "同时分析销售数据和用户数据"

**系统行为**:
1. 识别为并行分析任务
2. 同时执行两个分析任务
3. 汇总两个分析结果

## 配置选项

在 `application.yml` 中配置：

```yaml
smart-query:
  coordinator:
    enabled: true                    # 是否启用协调器
    parallel-execution: true         # 是否启用并行执行
    max-concurrent-tasks: 5          # 最大并行任务数
    task-timeout-seconds: 300        # 任务超时时间
    enable-task-retry: true          # 是否启用任务重试
    max-retry-attempts: 2            # 最大重试次数
    retry-delay-ms: 1000             # 重试延迟
```

## API 示例

### Java API

```java
@Autowired
private TaskCoordinator taskCoordinator;

// 创建任务列表
List<Task> tasks = new ArrayList<>();

// 任务1: 训练随机森林
tasks.add(Task.builder()
    .taskId("train_rf")
    .taskType("model_training")
    .description("训练随机森林")
    .parameters(Map.of("algorithm", "random_forest"))
    .dependencies(List.of())
    .build());

// 任务2: 训练XGBoost
tasks.add(Task.builder()
    .taskId("train_xgb")
    .taskType("model_training")
    .description("训练XGBoost")
    .parameters(Map.of("algorithm", "xgboost"))
    .dependencies(List.of())
    .build());

// 任务3: 对比结果（依赖前两个任务）
tasks.add(Task.builder()
    .taskId("compare")
    .taskType("model_comparison")
    .description("对比模型")
    .parameters(Map.of("models", List.of("random_forest", "xgboost")))
    .dependencies(List.of("train_rf", "train_xgb"))
    .build());

// 执行协调
List<TaskResult> results = taskCoordinator.coordinate("模型对比", tasks);

// 处理结果
for (TaskResult result : results) {
    if (result.isSuccess()) {
        System.out.println(result.getTaskId() + ": " + result.getData());
    } else {
        System.out.println(result.getTaskId() + " failed: " + result.getError());
    }
}
```

## 任务类型

### 当前支持的任务类型

1. **model_training**: 模型训练
2. **model_comparison**: 模型对比
3. **mining_task**: 数据挖掘任务
4. **data_query**: 数据查询
5. **data_analysis**: 数据分析
6. **chart_generation**: 图表生成

### 自定义任务类型

可以通过实现 `ModelTaskExecutor` 来添加新的任务类型。

## 监控和日志

协调器执行时会记录详细日志：

```
[COORDINATOR] Starting coordination for main task: 模型对比, subTasks: 3
[DAG-EXECUTOR] Task execution organized into 2 levels
[DAG-EXECUTOR] Executing level 1 with 2 tasks
[DAG-EXECUTOR] Executing task: train_random_forest (训练随机森林模型)
[DAG-EXECUTOR] Executing task: train_xgboost (训练XGBoost模型)
[DAG-EXECUTOR] Task completed: train_random_forest in 5234ms
[DAG-EXECUTOR] Task completed: train_xgboost in 4891ms
[DAG-EXECUTOR] Executing level 2 with 1 tasks
[DAG-EXECUTOR] Executing task: compare_models (对比模型性能)
[DAG-EXECUTOR] Task completed: compare_models in 1234ms
[COORDINATOR] Completed coordination for main task: 模型对比, results: 3
```

## 性能考虑

1. **并行执行**: 无依赖的任务会并行执行，可以显著减少总执行时间
2. **资源限制**: 通过 `max-concurrent-tasks` 控制并行度，避免资源耗尽
3. **超时控制**: 单个任务超时不会影响其他任务

## 故障处理

1. **任务失败**: 单个任务失败不会阻止其他任务执行
2. **重试机制**: 可配置自动重试失败的任务
3. **结果聚合**: 即使部分任务失败，也会返回已完成的任务结果

## 验证标准

### 模型对比验证

用户说: "对比随机森林和XGBoost两个模型"

预期行为:
1. ✅ 系统识别为协调任务
2. ✅ 两个模型训练任务并行执行
3. ✅ 对比任务在训练完成后执行
4. ✅ 返回对比结果表格
5. ✅ 总执行时间 < 串行执行时间

测试命令:
```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId": 1, "message": "对比随机森林和XGBoost两个模型"}'
```

## 扩展性

协调器设计支持扩展：

1. **新的任务类型**: 实现新的 TaskExecutor
2. **自定义协调策略**: 实现 TaskCoordinator 接口
3. **高级依赖管理**: 支持更复杂的依赖关系
4. **分布式执行**: 可扩展为跨机器执行
