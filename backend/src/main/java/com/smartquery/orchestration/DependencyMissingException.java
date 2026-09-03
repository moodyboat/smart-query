package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;

import java.util.List;
import java.util.Map;

public class DependencyMissingException extends BusinessException {
    private final String runtimeType;
    private final Long runtimeProfileId;
    private final List<Map<String, Object>> missing;

    public DependencyMissingException(String runtimeType, Long runtimeProfileId,
                                      List<Map<String, Object>> missing) {
        super(422, "DEPENDENCY_MISSING: " + missing.stream()
            .map(item -> item.get("name") + "@" + item.getOrDefault("version", "*"))
            .reduce((left, right) -> left + "、" + right).orElse("未知依赖"));
        this.runtimeType = runtimeType;
        this.runtimeProfileId = runtimeProfileId;
        this.missing = List.copyOf(missing);
    }

    public String runtimeType() { return runtimeType; }
    public Long runtimeProfileId() { return runtimeProfileId; }
    public List<Map<String, Object>> missing() { return missing; }
}
