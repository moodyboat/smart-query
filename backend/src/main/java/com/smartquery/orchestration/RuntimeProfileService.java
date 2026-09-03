package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OperatorVersionRuntimeBinding;
import com.smartquery.entity.RuntimeDependency;
import com.smartquery.entity.RuntimeProfile;
import com.smartquery.mapper.OperatorVersionRuntimeBindingMapper;
import com.smartquery.mapper.RuntimeDependencyMapper;
import com.smartquery.mapper.RuntimeProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves immutable runtime profiles and enforces dependency locks at publish and run time. */
@Service
@RequiredArgsConstructor
public class RuntimeProfileService {
    private static final Set<String> PYTHON_STDLIB = Set.of(
        "math", "statistics", "decimal", "datetime", "re", "json");
    private static final Pattern PYTHON_IMPORT = Pattern.compile(
        "(?m)^\\s*(?:from\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s+import|import\\s+([A-Za-z_][A-Za-z0-9_.]*))");

    private final RuntimeProfileMapper runtimeProfileMapper;
    private final RuntimeDependencyMapper runtimeDependencyMapper;
    private final OperatorVersionRuntimeBindingMapper bindingMapper;
    private final ObjectMapper objectMapper;

    public List<RuntimeProfile> listProfiles(String runtimeType, boolean includeDeprecated) {
        LambdaQueryWrapper<RuntimeProfile> query = new LambdaQueryWrapper<RuntimeProfile>()
            .orderByAsc(RuntimeProfile::getRuntimeType)
            .orderByDesc(RuntimeProfile::getCreatedAt);
        if (runtimeType != null && !runtimeType.isBlank()) query.eq(RuntimeProfile::getRuntimeType, runtimeType);
        if (!includeDeprecated) query.eq(RuntimeProfile::getStatus, "ACTIVE");
        return runtimeProfileMapper.selectList(query);
    }

    public RuntimeProfile defaultProfile(String runtimeType) {
        RuntimeProfile profile = runtimeProfileMapper.selectOne(new LambdaQueryWrapper<RuntimeProfile>()
            .eq(RuntimeProfile::getRuntimeType, runtimeType)
            .eq(RuntimeProfile::getStatus, "ACTIVE")
            .eq(RuntimeProfile::getDefaultProfile, 1)
            .orderByDesc(RuntimeProfile::getCreatedAt)
            .last("LIMIT 1"));
        if (profile == null) throw new BusinessException(503, "缺少可用默认运行时: " + runtimeType);
        return profile;
    }

    public RuntimeProfile resolveForVersion(String operatorType, String implementationType,
                                            Long requestedProfileId, Map<String, Object> payload) {
        String runtimeType = RuntimeTypes.forImplementation(operatorType, implementationType);
        RuntimeProfile profile = requestedProfileId == null
            ? defaultProfile(runtimeType) : requireProfile(requestedProfileId);
        if (!runtimeType.equals(profile.getRuntimeType())) {
            throw new BusinessException(422, "运行时类型不匹配，算子需要" + runtimeType);
        }
        if (!"ACTIVE".equals(profile.getStatus())) {
            throw new BusinessException(422, "新版本不能绑定非ACTIVE运行时: " + profile.getCode());
        }
        requireDependencies(profile, requirements(payload));
        return profile;
    }

    public void requireDependencies(RuntimeProfile profile, List<Map<String, Object>> requirements) {
        requireDependencies(profile, requirements, false);
    }

    private void requireDependencies(RuntimeProfile profile, List<Map<String, Object>> requirements,
                                     boolean allowDeprecated) {
        if (requirements == null || requirements.isEmpty()) return;
        List<RuntimeDependency> installed = dependencies(profile.getId());
        List<Map<String, Object>> missing = new ArrayList<>();
        for (Map<String, Object> requirement : requirements) {
            String type = text(requirement.get("type"));
            String name = text(requirement.get("name"));
            String version = text(requirement.get("version"));
            if (type == null || name == null) throw new BusinessException(422, "依赖必须包含type和name");
            String expectedRuntime = RuntimeTypes.DEPENDENCY_RUNTIME.get(type.toUpperCase(Locale.ROOT));
            if (expectedRuntime == null || !expectedRuntime.equals(profile.getRuntimeType())) {
                throw new BusinessException(422, "依赖" + type + "不能安装到" + profile.getRuntimeType());
            }
            boolean found = installed.stream().anyMatch(item ->
                type.equalsIgnoreCase(item.getDependencyType())
                    && name.equalsIgnoreCase(item.getDependencyName())
                    && ("ACTIVE".equals(item.getStatus())
                        || (allowDeprecated && "DEPRECATED".equals(item.getStatus())))
                    && versionMatches(version, item.getDependencyVersion()));
            if (!found) missing.add(Map.of("type", type.toUpperCase(Locale.ROOT), "name", name,
                "version", version == null ? "*" : version));
        }
        if (!missing.isEmpty()) {
            throw new DependencyMissingException(profile.getRuntimeType(), profile.getId(), missing);
        }
    }

    @Transactional
    public OperatorVersionRuntimeBinding bind(Long versionId, RuntimeProfile profile) {
        OperatorVersionRuntimeBinding existing = bindingMapper.selectOne(
            new LambdaQueryWrapper<OperatorVersionRuntimeBinding>()
                .eq(OperatorVersionRuntimeBinding::getOperatorVersionId, versionId)
                .last("LIMIT 1"));
        if (existing != null) {
            if (!profile.getId().equals(existing.getRuntimeProfileId())
                    || !profile.getImageDigest().equals(existing.getImageDigest())) {
                throw new BusinessException(409, "算子版本运行时绑定不可修改");
            }
            return existing;
        }
        OperatorVersionRuntimeBinding binding = new OperatorVersionRuntimeBinding();
        binding.setOperatorVersionId(versionId);
        binding.setRuntimeProfileId(profile.getId());
        binding.setRuntimeType(profile.getRuntimeType());
        binding.setImageDigest(profile.getImageDigest());
        bindingMapper.insert(binding);
        return binding;
    }

    public RuntimeBindingView requireRunnable(OperatorVersion version, String operatorType) {
        OperatorVersionRuntimeBinding binding = bindingMapper.selectOne(
            new LambdaQueryWrapper<OperatorVersionRuntimeBinding>()
                .eq(OperatorVersionRuntimeBinding::getOperatorVersionId, version.getId())
                .last("LIMIT 1"));
        if (binding == null) throw new BusinessException(422, "算子版本未绑定固定运行时: " + version.getId());
        RuntimeProfile profile = requireProfile(binding.getRuntimeProfileId());
        String expected = RuntimeTypes.forImplementation(operatorType, version.getImplementationType());
        if (!expected.equals(binding.getRuntimeType()) || !expected.equals(profile.getRuntimeType())) {
            throw new BusinessException(422, "算子版本运行时类型不匹配: " + version.getId());
        }
        if (!Set.of("ACTIVE", "DEPRECATED").contains(profile.getStatus())) {
            throw new BusinessException(422, "运行时已停止使用: " + profile.getCode());
        }
        if (!binding.getImageDigest().equals(profile.getImageDigest())) {
            throw new BusinessException(422, "运行时镜像摘要发生不一致");
        }
        requireDependencies(profile, requirements(parseMap(version.getImplementationPayload())), true);
        return new RuntimeBindingView(binding, profile, dependencies(profile.getId()));
    }

    public RuntimeBindingView binding(Long versionId) {
        OperatorVersionRuntimeBinding binding = bindingMapper.selectOne(
            new LambdaQueryWrapper<OperatorVersionRuntimeBinding>()
                .eq(OperatorVersionRuntimeBinding::getOperatorVersionId, versionId)
                .last("LIMIT 1"));
        if (binding == null) throw new BusinessException(404, "版本运行时绑定不存在: " + versionId);
        RuntimeProfile profile = requireProfile(binding.getRuntimeProfileId());
        return new RuntimeBindingView(binding, profile, dependencies(profile.getId()));
    }

    public RuntimeProfile requireProfile(Long id) {
        RuntimeProfile profile = id == null ? null : runtimeProfileMapper.selectById(id);
        if (profile == null) throw new BusinessException(404, "运行时档案不存在: " + id);
        return profile;
    }

    public List<RuntimeDependency> dependencies(Long profileId) {
        return runtimeDependencyMapper.selectList(new LambdaQueryWrapper<RuntimeDependency>()
            .eq(RuntimeDependency::getRuntimeProfileId, profileId)
            .orderByAsc(RuntimeDependency::getDependencyType)
            .orderByAsc(RuntimeDependency::getDependencyName));
    }

    public List<Map<String, Object>> inferRuleRequirements(String source) {
        LinkedHashSet<String> modules = new LinkedHashSet<>();
        Matcher matcher = PYTHON_IMPORT.matcher(source == null ? "" : source);
        while (matcher.find()) {
            String value = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            String root = value.split("\\.")[0];
            if (!PYTHON_STDLIB.contains(root)) modules.add(root);
        }
        return modules.stream().map(name -> Map.<String, Object>of(
            "type", "PYTHON_PACKAGE", "name", name, "version", "*")).toList();
    }

    public Set<String> allowedPythonModules(RuntimeProfile profile) {
        LinkedHashSet<String> result = new LinkedHashSet<>(PYTHON_STDLIB);
        dependencies(profile.getId()).stream()
            .filter(item -> "PYTHON_PACKAGE".equals(item.getDependencyType()))
            .map(RuntimeDependency::getDependencyName).forEach(result::add);
        return Set.copyOf(result);
    }

    public List<Map<String, Object>> requirements(Map<String, Object> payload) {
        Object raw = payload == null ? null : payload.get("dependencies");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new BusinessException(422, "dependencies必须是对象数组");
            Map<String, Object> value = new LinkedHashMap<>();
            map.forEach((key, element) -> value.put(String.valueOf(key), element));
            result.add(value);
        }
        return result;
    }

    private boolean versionMatches(String required, String installed) {
        return required == null || required.isBlank() || "*".equals(required) || required.equals(installed);
    }
    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { throw new BusinessException(422, "算子依赖声明损坏"); }
    }
    private String text(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    public record RuntimeBindingView(OperatorVersionRuntimeBinding binding,
                                     RuntimeProfile profile, List<RuntimeDependency> dependencies) {}
}
