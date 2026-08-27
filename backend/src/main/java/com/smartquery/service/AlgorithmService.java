package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.Algorithm;
import com.smartquery.mapper.AlgorithmMapper;
import com.smartquery.python.PythonSandbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlgorithmService {

    private static final Pattern ALGORITHM_ID = Pattern.compile("^[a-z][a-z0-9_]{2,99}$");

    private final AlgorithmMapper algorithmMapper;
    private final ObjectMapper objectMapper;

    private static final Map<String, String> ALIAS_MAP = Map.ofEntries(
        Map.entry("gbm", "gradient_boosting"),
        Map.entry("gb", "gradient_boosting"),
        Map.entry("rf", "random_forest"),
        Map.entry("xgb", "xgboost"),
        Map.entry("lgbm", "lightgbm"),
        Map.entry("cat", "catboost"),
        Map.entry("lr", "logistic_regression"),
        Map.entry("linreg", "linear_regression"),
        Map.entry("svr", "svm"),
        Map.entry("svc", "svm"),
        Map.entry("dt", "decision_tree"),
        Map.entry("knn", "k_neighbors"),
        Map.entry("nb", "naive_bayes"),
        Map.entry("mlp", "neural_network"),
        Map.entry("nn", "neural_network")
    );

    public List<Algorithm> getAll() {
        return algorithmMapper.selectList(
            new LambdaQueryWrapper<Algorithm>()
                .eq(Algorithm::getDeleted, 0)
                .orderByAsc(Algorithm::getIsBuiltin)
                .orderByAsc(Algorithm::getId));
    }

    public Algorithm getByAlgorithmId(String algorithmId) {
        Algorithm algo = algorithmMapper.selectOne(
            new LambdaQueryWrapper<Algorithm>()
                .eq(Algorithm::getAlgorithmId, algorithmId)
                .eq(Algorithm::getDeleted, 0));
        if (algo == null) {
            String resolved = ALIAS_MAP.get(algorithmId.toLowerCase());
            if (resolved != null) {
                algo = algorithmMapper.selectOne(
                    new LambdaQueryWrapper<Algorithm>()
                        .eq(Algorithm::getAlgorithmId, resolved)
                        .eq(Algorithm::getDeleted, 0));
                if (algo != null) {
                    log.debug("[ALGORITHM] Alias resolved: {} -> {}", algorithmId, resolved);
                }
            }
        }
        return algo;
    }

    public Algorithm getById(Long id) {
        return algorithmMapper.selectById(id);
    }

    @SuppressWarnings("unchecked")
    public List<Algorithm> getByModelType(String modelType) {
        List<Algorithm> all = getAll();
        return all.stream()
            .filter(a -> {
                try {
                    List<String> types = objectMapper.readValue(a.getModelTypes(), List.class);
                    return types.contains(modelType);
                } catch (Exception e) {
                    return false;
                }
            })
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
        Algorithm existing = getByAlgorithmId(algorithm.getAlgorithmId());
        if (existing != null) {
            throw new IllegalArgumentException("算法标识已存在: " + algorithm.getAlgorithmId());
        }
        validateDefinition(algorithm);
        algorithm.setIsBuiltin(0);
        algorithm.setDeleted(0);
        if (algorithm.getCategory() == null) algorithm.setCategory("自定义");
        if (algorithm.getIcon() == null) algorithm.setIcon("⭐");
        algorithmMapper.insert(algorithm);
        log.info("[ALGORITHM] Created custom algorithm: {} ({})", algorithm.getName(), algorithm.getAlgorithmId());
        return algorithm;
    }

    public Algorithm updateAlgorithm(Long id, Algorithm updates) {
        Algorithm existing = algorithmMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("算法不存在: " + id);
        if (existing.getIsBuiltin() == 1) {
            throw new IllegalArgumentException("内置算法不可修改");
        }
        if (updates.getAlgorithmId() != null
                && !updates.getAlgorithmId().equals(existing.getAlgorithmId())) {
            throw new IllegalArgumentException("算法标识创建后不可修改");
        }
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getModelTypes() != null) existing.setModelTypes(updates.getModelTypes());
        if (updates.getParamsSchema() != null) existing.setParamsSchema(updates.getParamsSchema());
        if (updates.getPythonCodeTemplate() != null) existing.setPythonCodeTemplate(updates.getPythonCodeTemplate());
        if (updates.getIcon() != null) existing.setIcon(updates.getIcon());
        if (updates.getCategory() != null) existing.setCategory(updates.getCategory());
        validateDefinition(existing);
        algorithmMapper.updateById(existing);
        return existing;
    }

    public void deleteAlgorithm(Long id) {
        Algorithm existing = algorithmMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("算法不存在: " + id);
        if (existing.getIsBuiltin() == 1) {
            throw new IllegalArgumentException("内置算法不可删除");
        }
        algorithmMapper.deleteById(id);
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
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("模型类型或参数定义不是有效 JSON", e);
        }
    }
}
