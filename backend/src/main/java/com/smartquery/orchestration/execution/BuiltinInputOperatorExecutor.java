package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.OperatorTypes;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Safe source for trial runs: it only forwards the submitted snapshot. */
@Component
public class BuiltinInputOperatorExecutor implements OperatorExecutor {
    @Override
    public String implementationType() {
        return "BUILTIN";
    }

    @Override
    public OperatorExecutionResult execute(OperatorExecutionContext context) {
        if (!OperatorTypes.DATA.equals(context.operatorType())) {
            throw new BusinessException(422, "BUILTIN执行器只能运行DATA算子");
        }
        String operation = String.valueOf(context.implementationPayload().getOrDefault("operation", ""));
        if (!"run_input".equals(operation)) {
            throw new BusinessException(422, "未启用的BUILTIN操作: " + operation);
        }
        List<Map<String, Object>> records = context.records();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("records", records);
        output.put("recordCount", records.size());
        return new OperatorExecutionResult(output, List.of(),
            "Forwarded " + records.size() + " immutable trial input records");
    }
}
