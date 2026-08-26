package com.smartquery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.Result;
import com.smartquery.entity.Algorithm;
import com.smartquery.service.AlgorithmService;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/mining")
@RequiredArgsConstructor
public class AlgorithmController {

    private final AlgorithmService algorithmService;
    private final ObjectMapper objectMapper;
    private final ResourceAccessService resourceAccess;

    private static final Map<String, String> TYPE_LABELS = Map.of(
        "classification", "分类", "regression", "回归", "clustering", "聚类", "anomaly_detection", "异常检测");
    private static final Map<String, String> TYPE_DESCS = Map.of(
        "classification", "将数据划分到预定义类别", "regression", "预测连续数值",
        "clustering", "无监督地将数据分组", "anomaly_detection", "识别与正常模式不同的数据点");

    @GetMapping("/algorithms")
    public Result<List<Algorithm>> listAlgorithms(
            @RequestParam(required = false) String modelType) {
        if (modelType != null && !modelType.isBlank()) {
            return Result.ok(algorithmService.getByModelType(modelType));
        }
        return Result.ok(algorithmService.getAll());
    }

    @GetMapping("/algorithms/{id}")
    public Result<Algorithm> getAlgorithm(@PathVariable Long id) {
        Algorithm algo = algorithmService.getById(id);
        if (algo == null) throw new BusinessException("算法不存在: " + id);
        return Result.ok(algo);
    }

    @GetMapping("/algorithms/categories")
    public Result<List<String>> listCategories() {
        return Result.ok(algorithmService.getCategories());
    }

    @PostMapping("/algorithms")
    public Result<Algorithm> createAlgorithm(@RequestBody Algorithm algorithm) {
        resourceAccess.requireAdmin();
        return Result.ok(algorithmService.createCustomAlgorithm(algorithm));
    }

    @PutMapping("/algorithms/{id}")
    public Result<Algorithm> updateAlgorithm(@PathVariable Long id, @RequestBody Algorithm updates) {
        resourceAccess.requireAdmin();
        return Result.ok(algorithmService.updateAlgorithm(id, updates));
    }

    @DeleteMapping("/algorithms/{id}")
    public Result<Void> deleteAlgorithm(@PathVariable Long id) {
        resourceAccess.requireAdmin();
        algorithmService.deleteAlgorithm(id);
        return Result.ok();
    }

    @GetMapping("/model-types")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, String>>> listModelTypes() {
        List<String> typeIds = new ArrayList<>();
        for (Algorithm a : algorithmService.getAll()) {
            try {
                List<String> types = objectMapper.readValue(a.getModelTypes(), List.class);
                for (String t : types) {
                    if (!typeIds.contains(t)) typeIds.add(t);
                }
            } catch (Exception ignored) {}
        }
        List<Map<String, String>> result = typeIds.stream()
            .map(id -> Map.of("id", id, "name", TYPE_LABELS.getOrDefault(id, id), "description", TYPE_DESCS.getOrDefault(id, id)))
            .collect(Collectors.toList());
        return Result.ok(result);
    }
}
