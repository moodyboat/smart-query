package com.smartquery.orchestration;

import java.util.Map;
import java.util.Set;

public final class RuntimeTypes {
    public static final String DATA_CONNECTOR = "DATA_CONNECTOR";
    public static final String RULE_PYTHON = "RULE_PYTHON";
    public static final String RULE_DSL = "RULE_DSL";
    public static final String ML_MODEL = "ML_MODEL";
    public static final String AGENT_GATEWAY = "AGENT_GATEWAY";
    public static final String OUTPUT_RENDERER = "OUTPUT_RENDERER";
    public static final Set<String> ALL = Set.of(DATA_CONNECTOR, RULE_PYTHON, RULE_DSL,
        ML_MODEL, AGENT_GATEWAY, OUTPUT_RENDERER);

    public static final Map<String, String> DEPENDENCY_RUNTIME = Map.of(
        "PYTHON_PACKAGE", RULE_PYTHON,
        "ML_ALGORITHM", ML_MODEL,
        "JDBC_DRIVER", DATA_CONNECTOR,
        "AGENT_TOOL", AGENT_GATEWAY,
        "FRONTEND_RENDERER", OUTPUT_RENDERER
    );

    private RuntimeTypes() {}

    public static String forImplementation(String operatorType, String implementationType) {
        if (OperatorTypes.RULE.equals(operatorType) && "SANDBOX_EXTENSION".equals(implementationType)) return RULE_PYTHON;
        if (OperatorTypes.RULE.equals(operatorType)) return RULE_DSL;
        return switch (operatorType) {
            case OperatorTypes.DATA -> DATA_CONNECTOR;
            case OperatorTypes.ML -> ML_MODEL;
            case OperatorTypes.AGENT -> AGENT_GATEWAY;
            case OperatorTypes.OUTPUT -> OUTPUT_RENDERER;
            default -> throw new IllegalArgumentException("未知算子类型: " + operatorType);
        };
    }
}
