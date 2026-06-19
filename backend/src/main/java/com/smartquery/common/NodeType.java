package com.smartquery.common;

/**
 * Pipeline 节点类型常量（与前端 constants.js 的 NODE_TYPES 对应）。
 */
public final class NodeType {
    private NodeType() {}

    public static final String DATA_SOURCE = "data_source";
    public static final String PREPROCESSING = "preprocessing";
    public static final String FILL_MISSING = "fill_missing";
    public static final String FEATURE_ENGINEERING = "feature_engineering";
    public static final String TRAINING = "training";
    public static final String EVALUATION = "evaluation";
    public static final String OUTPUT = "output";
}
