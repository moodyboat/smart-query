package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.Algorithm;
import com.smartquery.mapper.AlgorithmMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlgorithmService {

    private final AlgorithmMapper algorithmMapper;
    private final ObjectMapper objectMapper;

    public List<Algorithm> getAll() {
        return algorithmMapper.selectList(
            new LambdaQueryWrapper<Algorithm>()
                .eq(Algorithm::getDeleted, 0)
                .orderByAsc(Algorithm::getIsBuiltin)
                .orderByAsc(Algorithm::getId));
    }

    public Algorithm getByAlgorithmId(String algorithmId) {
        return algorithmMapper.selectOne(
            new LambdaQueryWrapper<Algorithm>()
                .eq(Algorithm::getAlgorithmId, algorithmId)
                .eq(Algorithm::getDeleted, 0));
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
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getModelTypes() != null) existing.setModelTypes(updates.getModelTypes());
        if (updates.getParamsSchema() != null) existing.setParamsSchema(updates.getParamsSchema());
        if (updates.getPythonCodeTemplate() != null) existing.setPythonCodeTemplate(updates.getPythonCodeTemplate());
        if (updates.getIcon() != null) existing.setIcon(updates.getIcon());
        if (updates.getCategory() != null) existing.setCategory(updates.getCategory());
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
}
