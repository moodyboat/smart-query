package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bridges machine-learning training artifacts into the unified operator catalog. */
@Service
@RequiredArgsConstructor
public class MiningOperatorRegistrationService {
    private final VersionCatalogService versionCatalogService;
    private final OperatorApprovalService operatorApprovalService;

    /**
     * Register the business object as an operator as soon as its training draft is created.
     * The MiningModel row remains the mutable training/artifact record behind this definition.
     */
    @Transactional
    public OperatorDefinition ensureOperator(MiningModel model) {
        requireValidModel(model);
        String ownerUserId = UserContextHolder.require().userId().toString();
        String code = operatorCode(model.getId());
        OperatorDefinition operator = versionCatalogService.listOperators().stream()
            .filter(item -> code.equals(item.getCode()) && ownerUserId.equals(item.getOwnerUserId()))
            .findFirst()
            .orElse(null);
        if (operator == null) {
            return versionCatalogService.createOperator(Map.of(
                "code", code,
                "name", model.getName(),
                "description", description(model),
                "operatorType", OperatorTypes.ML));
        }
        if (!model.getName().equals(operator.getName()) || !description(model).equals(operator.getDescription())) {
            return versionCatalogService.updateOperatorMetadata(operator.getId(), model.getName(), description(model));
        }
        return operator;
    }

    @Transactional
    public void archiveOperator(MiningModel model) {
        if (model == null || model.getId() == null) return;
        String code = operatorCode(model.getId());
        versionCatalogService.listOperators().stream()
            .filter(item -> code.equals(item.getCode()))
            .findFirst()
            .ifPresent(item -> versionCatalogService.archiveOperator(item.getId()));
    }

    @Transactional
    public OperatorVersion registerPublishedModel(MiningModel model) {
        OperatorDefinition operator = ensureOperator(model);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelId", model.getId());
        payload.put("modelVersion", model.getVersion());
        payload.put("modelType", model.getModelType());
        payload.put("algorithm", model.getAlgorithm());
        payload.put("algorithmVersion", model.getAlgorithmVersion());
        payload.put("sourceTable", model.getSourceTable());
        payload.put("targetColumn", model.getTargetColumn());
        payload.put("validationMode", model.getValidationMode());
        payload.put("metrics", model.getMetrics());
        payload.put("validationMetrics", model.getValidationMetrics());
        payload.put("governancePolicy", model.getGovernancePolicy());
        payload.put("evaluationStatus", model.getEvaluationStatus());
        payload.put("artifactSchemaVersion", model.getArtifactSchemaVersion());
        if (model.getArtifactSha256() != null) payload.put("artifactSha256", model.getArtifactSha256());
        payload.put("predictionField", "prediction");
        payload.put("probabilityField", "predictionProbability");

        Map<String, Object> recordArray = Map.of(
            "type", "array",
            "items", Map.of("type", "object"));
        OperatorVersion version = versionCatalogService.createOperatorVersion(operator.getId(), Map.of(
            "inputSchema", Map.of(
                "type", "object",
                "required", List.of("records"),
                "properties", Map.of("records", recordArray)),
            "outputSchema", Map.of(
                "type", "object",
                "required", List.of("records"),
                "properties", Map.of("records", recordArray)),
            "parameterSchema", Map.of(),
            "implementationType", "MINING_RUNTIME",
            "implementationPayload", payload));
        operatorApprovalService.submit(operator.getId(), version.getId(),
            "训练制品已固化，提交机器学习算子版本审批");
        return version;
    }

    private void requireValidModel(MiningModel model) {
        if (model == null || model.getId() == null || model.getName() == null || model.getName().isBlank()) {
            throw new BusinessException(422, "无法登记无效的机器学习算子");
        }
    }

    private String operatorCode(Long modelId) {
        // Retain the established code for compatibility with existing versions and DAG references.
        return "ml_model_" + modelId;
    }

    private String description(MiningModel model) {
        if (model.getDescription() != null && !model.getDescription().isBlank()) {
            return model.getDescription().trim();
        }
        return "通过数据训练生成的机器学习算子“" + model.getName() + "”";
    }
}
