package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.RuntimeDependency;
import com.smartquery.entity.RuntimeProfile;
import com.smartquery.mapper.OperatorVersionRuntimeBindingMapper;
import com.smartquery.mapper.RuntimeDependencyMapper;
import com.smartquery.mapper.RuntimeProfileMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeProfileServiceTest {
    private final RuntimeProfileMapper profiles = mock(RuntimeProfileMapper.class);
    private final RuntimeDependencyMapper dependencies = mock(RuntimeDependencyMapper.class);
    private final RuntimeProfileService service = new RuntimeProfileService(
        profiles, dependencies, mock(OperatorVersionRuntimeBindingMapper.class), new ObjectMapper());

    @Test
    void ruleImportsBecomeExplicitPackageRequirements() {
        List<Map<String, Object>> result = service.inferRuleRequirements("""
            import math
            import pandas as pd
            from sklearn.ensemble import RandomForestClassifier
            """);

        assertEquals(List.of("pandas", "sklearn"),
            result.stream().map(item -> String.valueOf(item.get("name"))).toList());
    }

    @Test
    void missingDependencyClosesRuntimeSelection() {
        RuntimeProfile profile = profile(7L, RuntimeTypes.RULE_PYTHON);
        when(profiles.selectById(7L)).thenReturn(profile);
        when(dependencies.selectList(any())).thenReturn(List.of());

        DependencyMissingException error = assertThrows(DependencyMissingException.class,
            () -> service.resolveForVersion(OperatorTypes.RULE, "SANDBOX_EXTENSION", 7L,
                Map.of("dependencies", List.of(Map.of(
                    "type", "PYTHON_PACKAGE", "name", "pandas", "version", "2.2.3")))));

        assertEquals("pandas", error.missing().get(0).get("name"));
    }

    @Test
    void exactLockedDependencyAllowsSelection() {
        RuntimeProfile profile = profile(8L, RuntimeTypes.ML_MODEL);
        RuntimeDependency installed = new RuntimeDependency();
        installed.setDependencyType("ML_ALGORITHM");
        installed.setDependencyName("xgboost");
        installed.setDependencyVersion("3.0.2");
        installed.setStatus("ACTIVE");
        when(profiles.selectById(8L)).thenReturn(profile);
        when(dependencies.selectList(any())).thenReturn(List.of(installed));

        RuntimeProfile selected = service.resolveForVersion(OperatorTypes.ML, "MINING_RUNTIME", 8L,
            Map.of("dependencies", List.of(Map.of(
                "type", "ML_ALGORITHM", "name", "xgboost", "version", "3.0.2"))));

        assertEquals(8L, selected.getId());
        assertTrue(service.requirements(Map.of("dependencies", List.of())).isEmpty());
    }

    private RuntimeProfile profile(Long id, String type) {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(id);
        profile.setRuntimeType(type);
        profile.setStatus("ACTIVE");
        profile.setCode("test-runtime-" + id);
        profile.setImageDigest("sha256:" + "a".repeat(64));
        return profile;
    }
}
