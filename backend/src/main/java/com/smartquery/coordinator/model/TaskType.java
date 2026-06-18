package com.smartquery.coordinator.model;

/**
 * 任务类型枚举
 */
public enum TaskType {
    /**
     * 模型训练任务
     */
    MODEL_TRAINING("model_training", "模型训练"),

    /**
     * 模型对比任务
     */
    MODEL_COMPARISON("model_comparison", "模型对比"),

    /**
     * 数据查询任务
     */
    DATA_QUERY("data_query", "数据查询"),

    /**
     * 数据分析任务
     */
    DATA_ANALYSIS("data_analysis", "数据分析"),

    /**
     * 图表生成任务
     */
    CHART_GENERATION("chart_generation", "图表生成"),

    /**
     * 挖掘任务
     */
    MINING_TASK("mining_task", "数据挖掘"),

    /**
     * 通用任务
     */
    GENERAL("general", "通用任务");

    private final String code;
    private final String description;

    TaskType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static TaskType fromCode(String code) {
        for (TaskType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return GENERAL;
    }
}
