package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.entity.Algorithm;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.MiningPipeline;
import com.smartquery.mapper.AlgorithmMapper;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.MiningPipelineMapper;
import com.smartquery.python.PythonSandbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlgorithmService {

    private static final Pattern ALGORITHM_ID = Pattern.compile("^[a-z][a-z0-9_]{2,99}$");

    private final AlgorithmMapper algorithmMapper;
    private final MiningModelMapper miningModelMapper;
    private final MiningPipelineMapper miningPipelineMapper;
    private final ObjectMapper objectMapper;

    /** Active algorithms available to model and pipeline editors. */
    public List<Algorithm> getAll() {
        return getAllIncludingDisabled().stream()
            .filter(this::isEnabled)
            .toList();
    }

    /** Complete governance view, including disabled algorithms and reference counts. */
    public List<Algorithm> getAllForManagement() {
        List<Algorithm> algorithms = getAllIncludingDisabled();
        ReferenceInventory inventory = loadReferenceInventory();
        algorithms.forEach(algorithm -> enrichGovernance(algorithm, inventory));
        return algorithms;
    }

    public Algorithm getByAlgorithmId(String algorithmId) {
        if (algorithmId == null || algorithmId.isBlank()) return null;
        String requested = normalize(algorithmId);
        for (Algorithm candidate : getAll()) {
            if (normalize(candidate.getAlgorithmId()).equals(requested)) return candidate;
            if (aliases(candidate).contains(requested)) {
                log.debug("[ALGORITHM] database alias resolved: {} -> {}", algorithmId,
                    candidate.getAlgorithmId());
                return candidate;
            }
        }
        return null;
    }

    public Algorithm getById(Long id) {
        Algorithm algorithm = algorithmMapper.selectById(id);
        if (algorithm == null || Objects.equals(algorithm.getDeleted(), 1)) return null;
        enrichGovernance(algorithm);
        return algorithm;
    }

    public List<Algorithm> getByModelType(String modelType) {
        return getAll().stream()
            .filter(a -> supportedModelTypes(a).contains(modelType))
            .collect(Collectors.toList());
    }

    public List<String> getCategories() {
        return getAll().stream()
            .map(Algorithm::getCategory)
            .filter(c -> c != null && !c.isBlank())
            .distinct()
            .collect(Collectors.toList());
    }

    public Algorithm createCustomAlgorithm(Algorithm algorithm) {
        if (algorithm.getAlgorithmId() == null || algorithm.getAlgorithmId().isBlank()) {
            throw new IllegalArgumentException("algorithm_id 不能为空");
        }
        algorithm.setAlgorithmId(normalize(algorithm.getAlgorithmId()));
        validateDefinition(algorithm);
        ensureIdentityAvailable(algorithm, null);
        algorithm.setIsBuiltin(0);
        algorithm.setEnabled(1);
        algorithm.setVersionNo(1);
        algorithm.setDeleted(0);
        if (algorithm.getCategory() == null) algorithm.setCategory("自定义");
        if (algorithm.getIcon() == null) algorithm.setIcon("⭐");
        algorithmMapper.insert(algorithm);
        log.info("[ALGORITHM] Created custom algorithm: {} ({})", algorithm.getName(), algorithm.getAlgorithmId());
        return enrichGovernance(algorithm);
    }

    public Algorithm updateAlgorithm(Long id, Algorithm updates) {
        Algorithm existing = requireExisting(id);
        if (Objects.equals(existing.getIsBuiltin(), 1)) {
            throw new IllegalArgumentException("内置算法由版本化目录管理，不可在线修改");
        }
        if (updates.getAlgorithmId() != null
                && !normalize(updates.getAlgorithmId()).equals(existing.getAlgorithmId())) {
            throw new IllegalArgumentException("算法标识创建后不可修改");
        }
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getModelTypes() != null) existing.setModelTypes(updates.getModelTypes());
        if (updates.getParamsSchema() != null) existing.setParamsSchema(updates.getParamsSchema());
        if (updates.getPythonCodeTemplate() != null) existing.setPythonCodeTemplate(updates.getPythonCodeTemplate());
        if (updates.getAliases() != null) existing.setAliases(updates.getAliases());
        if (updates.getIcon() != null) existing.setIcon(updates.getIcon());
        if (updates.getCategory() != null) existing.setCategory(updates.getCategory());
        validateDefinition(existing);
        ensureIdentityAvailable(existing, id);
        existing.setVersionNo(Math.max(1, Objects.requireNonNullElse(existing.getVersionNo(), 1)) + 1);
        algorithmMapper.updateById(existing);
        log.info("[ALGORITHM] Updated custom algorithm {} to v{}", existing.getAlgorithmId(), existing.getVersionNo());
        return enrichGovernance(existing);
    }

    public Algorithm setEnabled(Long id, boolean enabled) {
        Algorithm existing = requireExisting(id);
        existing.setEnabled(enabled ? 1 : 0);
        algorithmMapper.updateById(existing);
        log.info("[ALGORITHM] {} algorithm {}", enabled ? "Enabled" : "Disabled", existing.getAlgorithmId());
        return enrichGovernance(existing);
    }

    public void deleteAlgorithm(Long id) {
        Algorithm existing = requireExisting(id);
        if (Objects.equals(existing.getIsBuiltin(), 1)) {
            throw new IllegalArgumentException("内置算法不可删除，可以停用");
        }
        if (isEnabled(existing)) {
            throw new IllegalArgumentException("请先停用算法，再执行删除");
        }
        enrichGovernance(existing);
        if (existing.getTotalReferenceCount() > 0) {
            throw new IllegalArgumentException(String.format(
                "算法仍被引用：模型 %d 个（已发布 %d 个），流程 %d 个，不能删除",
                existing.getModelReferenceCount(), existing.getPublishedModelReferenceCount(),
                existing.getPipelineReferenceCount()));
        }
        algorithmMapper.deleteById(id);
        log.info("[ALGORITHM] Soft-deleted custom algorithm {}", existing.getAlgorithmId());
    }

    /** Build an immutable, checksum-protected definition used by a training run. */
    public AlgorithmBinding activeBinding(String requestedId, String modelType) {
        Algorithm algorithm = getByAlgorithmId(requestedId);
        if (algorithm == null) throw new IllegalArgumentException("算法不存在或已停用: " + requestedId);
        if (modelType != null && !modelType.isBlank() && !supportedModelTypes(algorithm).contains(modelType)) {
            throw new IllegalArgumentException("算法 " + algorithm.getName() + " 不支持模型类型: " + modelType);
        }
        String code = algorithm.getPythonCodeTemplate();
        int version = Math.max(1, Objects.requireNonNullElse(algorithm.getVersionNo(), 1));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("algorithmId", algorithm.getAlgorithmId());
        snapshot.put("name", algorithm.getName());
        snapshot.put("versionNo", version);
        snapshot.put("modelTypes", supportedModelTypes(algorithm));
        snapshot.put("paramsSchema", parseJson(algorithm.getParamsSchema(), "算法参数定义"));
        snapshot.put("pythonCodeTemplate", code);
        snapshot.put("codeSha256", sha256(code));
        try {
            return new AlgorithmBinding(algorithm.getAlgorithmId(), version, code,
                objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            throw new IllegalArgumentException("算法快照生成失败", e);
        }
    }

    /** Resolve a model's saved definition; old models are upgraded lazily from the active catalog. */
    public AlgorithmBinding resolveModelBinding(MiningModel model) {
        if (model.getAlgorithmSnapshot() == null || model.getAlgorithmSnapshot().isBlank()) {
            return activeBinding(model.getAlgorithm(), model.getModelType());
        }
        try {
            JsonNode snapshot = objectMapper.readTree(model.getAlgorithmSnapshot());
            String id = snapshot.path("algorithmId").asText();
            String code = snapshot.path("pythonCodeTemplate").asText();
            int version = snapshot.path("versionNo").asInt(1);
            String expectedHash = snapshot.path("codeSha256").asText();
            if (id.isBlank() || code.isBlank() || expectedHash.isBlank()
                    || !MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8),
                        sha256(code).getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("模型的算法快照不完整或校验失败");
            }
            PythonSandbox.validate(code);
            return new AlgorithmBinding(id, version, code, model.getAlgorithmSnapshot());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("模型的算法快照不是有效 JSON", e);
        }
    }

    public void applyBinding(MiningModel model, AlgorithmBinding binding) {
        model.setAlgorithm(binding.algorithmId());
        model.setAlgorithmVersion(binding.versionNo());
        model.setAlgorithmSnapshot(binding.snapshot());
    }

    public record AlgorithmBinding(String algorithmId, int versionNo,
                                   String pythonCodeTemplate, String snapshot) {}

    private List<Algorithm> getAllIncludingDisabled() {
        return algorithmMapper.selectList(
            new LambdaQueryWrapper<Algorithm>()
                .eq(Algorithm::getDeleted, 0)
                .orderByDesc(Algorithm::getIsBuiltin)
                .orderByAsc(Algorithm::getId));
    }

    private Algorithm requireExisting(Long id) {
        Algorithm existing = algorithmMapper.selectById(id);
        if (existing == null || Objects.equals(existing.getDeleted(), 1)) {
            throw new IllegalArgumentException("算法不存在: " + id);
        }
        return existing;
    }

    private Algorithm enrichGovernance(Algorithm algorithm) {
        return enrichGovernance(algorithm, loadReferenceInventory());
    }

    private ReferenceInventory loadReferenceInventory() {
        List<MiningModel> models = miningModelMapper.selectList(
            new LambdaQueryWrapper<MiningModel>().eq(MiningModel::getDeleted, 0));
        List<MiningPipeline> pipelines = miningPipelineMapper.selectList(
            new LambdaQueryWrapper<MiningPipeline>().eq(MiningPipeline::getDeleted, 0));
        return new ReferenceInventory(
            models == null ? List.of() : models,
            pipelines == null ? List.of() : pipelines);
    }

    private Algorithm enrichGovernance(Algorithm algorithm, ReferenceInventory inventory) {
        Set<String> identities = identities(algorithm);
        long modelCount = 0;
        long publishedModelCount = 0;
        for (MiningModel model : inventory.models()) {
            if (identities.contains(normalize(model.getAlgorithm()))) {
                modelCount++;
                if (ModelStatus.PUBLISHED.equals(model.getStatus())) publishedModelCount++;
            }
        }

        long pipelineCount = 0;
        for (MiningPipeline pipeline : inventory.pipelines()) {
            if (pipelineReferences(pipeline.getNodes(), identities)) pipelineCount++;
        }
        algorithm.setModelReferenceCount(modelCount);
        algorithm.setPublishedModelReferenceCount(publishedModelCount);
        algorithm.setPipelineReferenceCount(pipelineCount);
        algorithm.setTotalReferenceCount(modelCount + pipelineCount);
        algorithm.setDeletable(!Objects.equals(algorithm.getIsBuiltin(), 1)
            && !isEnabled(algorithm) && modelCount + pipelineCount == 0);
        return algorithm;
    }

    private record ReferenceInventory(List<MiningModel> models, List<MiningPipeline> pipelines) {}

    private boolean pipelineReferences(String nodesJson, Set<String> identities) {
        if (nodesJson == null || nodesJson.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(nodesJson);
            for (JsonNode value : root.findValues("algorithm")) {
                if (value.isTextual() && identities.contains(normalize(value.asText()))) return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("[ALGORITHM] Could not inspect pipeline algorithm reference: {}", e.getMessage());
            return false;
        }
    }

    private void ensureIdentityAvailable(Algorithm candidate, Long ownId) {
        Set<String> requested = identities(candidate);
        for (Algorithm existing : getAllIncludingDisabled()) {
            if (Objects.equals(existing.getId(), ownId)) continue;
            Set<String> overlap = new LinkedHashSet<>(identities(existing));
            overlap.retainAll(requested);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException("算法标识或别名已被占用: " + overlap.iterator().next());
            }
        }
    }

    private Set<String> identities(Algorithm algorithm) {
        Set<String> result = new LinkedHashSet<>();
        result.add(normalize(algorithm.getAlgorithmId()));
        result.addAll(aliases(algorithm));
        result.remove("");
        return result;
    }

    private void validateDefinition(Algorithm algorithm) {
        if (algorithm.getAlgorithmId() == null
                || !ALGORITHM_ID.matcher(algorithm.getAlgorithmId()).matches()) {
            throw new IllegalArgumentException("算法标识只能由小写字母、数字和下划线组成，且必须以字母开头");
        }
        if (algorithm.getName() == null || algorithm.getName().isBlank()) {
            throw new IllegalArgumentException("算法名称不能为空");
        }
        if (algorithm.getPythonCodeTemplate() == null || algorithm.getPythonCodeTemplate().isBlank()) {
            throw new IllegalArgumentException("Python 模板不能为空");
        }
        PythonSandbox.validate(algorithm.getPythonCodeTemplate());
        try {
            List<?> types = objectMapper.readValue(algorithm.getModelTypes(), List.class);
            if (types.isEmpty()) throw new IllegalArgumentException("至少选择一种模型类型");
            Object params = objectMapper.readValue(algorithm.getParamsSchema(), Object.class);
            if (!(params instanceof List<?>) && !(params instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("参数定义必须是 JSON 数组或 Schema 对象");
            }
            aliases(algorithm);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("模型类型或参数定义不是有效 JSON", e);
        }
    }

    private List<String> supportedModelTypes(Algorithm algorithm) {
        try {
            return objectMapper.readValue(algorithm.getModelTypes(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("算法模型类型配置无效: " + algorithm.getAlgorithmId(), e);
        }
    }

    private Object parseJson(String json, String label) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(label + "不是有效 JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> aliases(Algorithm algorithm) {
        if (algorithm.getAliases() == null || algorithm.getAliases().isBlank()) return List.of();
        try {
            List<String> values = objectMapper.readValue(algorithm.getAliases(), List.class);
            List<String> result = new ArrayList<>();
            for (String value : values) {
                String normalized = normalize(value);
                if (!normalized.isBlank() && !result.contains(normalized)) result.add(normalized);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("算法别名不是有效 JSON 数组: " + algorithm.getAlgorithmId(), e);
        }
    }

    private boolean isEnabled(Algorithm algorithm) {
        return !Objects.equals(algorithm.getEnabled(), 0);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
