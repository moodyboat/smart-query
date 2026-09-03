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

/** Bridges the existing model lifecycle into the unified V2 operator catalog. */
@Service
@RequiredArgsConstructor
public class MiningOperatorRegistrationService {
    private final VersionCatalogService versionCatalogService;
    private final OperatorApprovalService operatorApprovalService;

    @Transactional
    public OperatorVersion registerPublishedModel(MiningModel model) {
        if (model == null || model.getId() == null) {
            throw new BusinessException(422, "无法为无效模型创建机器学习算子版本");
        }
        String ownerUserId = UserContextHolder.require().userId().toString();
        String code = "ml_model_" + model.getId();
        OperatorDefinition operator = versionCatalogService.listOperators().stream()
            .filter(item -> code.equals(item.getCode()) && ownerUserId.equals(item.getOwnerUserId()))
            .findFirst()
            .orElseGet(() -> versionCatalogService.createOperator(Map.of(
                "code", code,
                "name", model.getName(),
                "description", description(model),
                "operatorType", OperatorTypes.ML)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelId", model.getId());
        payload.put("modelVersion", model.getVersion());
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
            "模型发布完成，提交机器学习算子版本审批");
        return version;
    }

    private String description(MiningModel model) {
        if (model.getDescription() != null && !model.getDescription().isBlank()) {
            return model.getDescription().trim();
        }
        return "由数据挖掘模型“" + model.getName() + "”生成的机器学习算子";
    }
}
