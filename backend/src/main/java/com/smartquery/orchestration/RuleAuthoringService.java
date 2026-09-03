package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.RuleDraft;
import com.smartquery.llm.LlmService;
import com.smartquery.mapper.ChatMessageMapper;
import com.smartquery.mapper.RuleDraftMapper;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dialogue-to-code authoring boundary. Generated code is never executed here. */
@Service
@RequiredArgsConstructor
public class RuleAuthoringService {
    private final RuleDraftMapper ruleDraftMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ResourceAccessService resourceAccessService;
    private final VersionCatalogService versionCatalogService;
    private final RuleRuntimeClient ruleRuntimeClient;
    private final RuntimeProfileService runtimeProfileService;
    private final DependencyCenterService dependencyCenterService;
    private final OperatorApprovalService operatorApprovalService;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    @Value("${smart-query.llm.default-model:glm-5.1}")
    private String defaultModel;

    public List<RuleDraft> list(Long operatorId) {
        requireRuleOperator(operatorId);
        return ruleDraftMapper.selectList(new LambdaQueryWrapper<RuleDraft>()
            .eq(RuleDraft::getOperatorId, operatorId)
            .orderByDesc(RuleDraft::getCreatedAt));
    }

    @Transactional
    public RuleDraft generate(Long operatorId, Map<String, Object> body) {
        requireRuleOperator(operatorId);
        String instruction = required(body, "instruction");
        Long conversationId = number(body.get("conversationId"));
        if (conversationId != null) resourceAccessService.requireConversation(conversationId);
        Long basedOnVersionId = number(body.get("basedOnVersionId"));
        Map<String, Object> previousArtifact = Map.of();
        if (basedOnVersionId != null) {
            OperatorVersion previous = versionCatalogService.requireOperatorVersionVisible(basedOnVersionId);
            if (!operatorId.equals(previous.getOperatorId())) {
                throw new BusinessException(422, "basedOnVersionId不属于当前规则算子");
            }
            previousArtifact = object(previous.getImplementationPayload(), "已有规则工件");
        }

        Map<String, Object> requestedInputSchema = map(body.getOrDefault("inputSchema", Map.of()));
        Map<String, Object> requestedOutputSchema = map(body.getOrDefault("outputSchema", Map.of()));
        String prompt = authoringPrompt(instruction, conversationContext(conversationId),
            previousArtifact, requestedInputSchema, requestedOutputSchema);
        String model = text(body.get("model"));
        String response = llmService.chat(model == null ? defaultModel : model,
            List.of(Map.of("role", "system", "content", "你是规则算子工程师，只返回严格JSON。"),
                Map.of("role", "user", "content", prompt)));
        Map<String, Object> artifact = parseArtifact(response);
        validateArtifact(artifact);

        RuleDraft draft = new RuleDraft();
        draft.setOperatorId(operatorId);
        draft.setConversationId(conversationId);
        draft.setBasedOnVersionId(basedOnVersionId);
        draft.setInstructionText(instruction);
        draft.setSourceLanguage(String.valueOf(artifact.get("language")));
        draft.setEntrypoint(String.valueOf(artifact.get("entrypoint")));
        draft.setSourceCode(String.valueOf(artifact.get("sourceCode")));
        draft.setInputSchema(json(artifact.getOrDefault("inputSchema", requestedInputSchema)));
        draft.setOutputSchema(json(artifact.getOrDefault("outputSchema", requestedOutputSchema)));
        draft.setParameterSchema(json(artifact.getOrDefault("parameterSchema", Map.of())));
        draft.setTestCases(json(artifact.get("tests")));
        draft.setExplanation(text(artifact.get("explanation")));
        draft.setStatus("GENERATED");
        draft.setValidationReport(json(Map.of("valid", true, "executionMode", "ISOLATED_SANDBOX",
            "warnings", List.of("尚未执行沙箱测试，不能发布生产"))));
        draft.setCreatedByUserId(currentUserId());
        ruleDraftMapper.insert(draft);
        return draft;
    }

    @Transactional
    public OperatorVersion createCandidateVersion(Long operatorId, Long draftId) {
        requireRuleOperator(operatorId);
        RuleDraft draft = requireDraft(operatorId, draftId);
        if (!"VALIDATED".equals(draft.getStatus())) {
            throw new BusinessException(422, "规则草稿必须先通过隔离沙箱测试才能生成候选版本");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("language", draft.getSourceLanguage());
        payload.put("entrypoint", draft.getEntrypoint());
        payload.put("sourceCode", draft.getSourceCode());
        payload.put("tests", parseList(draft.getTestCases()));
        payload.put("draftId", draft.getId());
        payload.put("conversationId", draft.getConversationId());
        payload.put("sandboxValidated", true);
        Map<String, Object> validationReport = object(draft.getValidationReport(), "validationReport");
        payload.put("sandboxValidationReport", validationReport);
        payload.put("dependencies", validationReport.getOrDefault("dependencies", List.of()));
        payload.put("allowedModules", validationReport.getOrDefault("allowedModules", List.of()));
        Map<String, Object> versionBody = new LinkedHashMap<>();
        versionBody.put("inputSchema", object(draft.getInputSchema(), "inputSchema"));
        versionBody.put("outputSchema", object(draft.getOutputSchema(), "outputSchema"));
        versionBody.put("parameterSchema", object(draft.getParameterSchema(), "parameterSchema"));
        versionBody.put("implementationType", "SANDBOX_EXTENSION");
        versionBody.put("implementationPayload", payload);
        versionBody.put("runtimeProfileId", validationReport.get("runtimeProfileId"));
        OperatorVersion version = versionCatalogService.createOperatorVersion(operatorId, versionBody);
        operatorApprovalService.submitFromDraft(operatorId, version.getId(), "RULE", draft.getId(),
            "规则草稿已通过隔离沙箱测试");
        if (!VersionStatus.PUBLISHED.equals(version.getStatus())) version.setStatus(VersionStatus.PENDING_APPROVAL);
        draft.setCandidateVersionId(version.getId());
        draft.setStatus(VersionStatus.PUBLISHED.equals(version.getStatus()) ? "PUBLISHED" : "PENDING_APPROVAL");
        ruleDraftMapper.updateById(draft);
        return version;
    }

    @Transactional
    public RuleDraft validateDraft(Long operatorId, Long draftId) {
        return validateDraft(operatorId, draftId, null);
    }

    @Transactional
    public RuleDraft validateDraft(Long operatorId, Long draftId, Long runtimeProfileId) {
        requireRuleOperator(operatorId);
        RuleDraft draft = requireDraft(operatorId, draftId);
        if ("PENDING_APPROVAL".equals(draft.getStatus())
            || "PUBLISHED".equals(draft.getStatus())
            || "APPROVAL_REJECTED".equals(draft.getStatus())) {
            throw new BusinessException(409, "已提交审批的规则草稿不可重新验证，请通过对话生成新草稿");
        }
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("language", draft.getSourceLanguage());
        artifact.put("entrypoint", draft.getEntrypoint());
        artifact.put("sourceCode", draft.getSourceCode());
        artifact.put("tests", parseList(draft.getTestCases()));
        List<Map<String, Object>> dependencies = runtimeProfileService.inferRuleRequirements(draft.getSourceCode());
        artifact.put("dependencies", dependencies);
        com.smartquery.entity.RuntimeProfile profile;
        try {
            profile = runtimeProfileService.resolveForVersion(
                OperatorTypes.RULE, "SANDBOX_EXTENSION", runtimeProfileId, artifact);
        } catch (DependencyMissingException missing) {
            draft.setStatus("DEPENDENCY_MISSING");
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("valid", false);
            report.put("code", "DEPENDENCY_MISSING");
            report.put("runtimeType", missing.runtimeType());
            report.put("runtimeProfileId", missing.runtimeProfileId());
            report.put("dependencies", dependencies);
            report.put("missingDependencies", missing.missing());
            report.put("error", missing.getMessage());
            draft.setValidationReport(json(report));
            ruleDraftMapper.updateById(draft);
            dependencyCenterService.markDraftMissing("RULE", draft.getId(), missing.missing());
            return draft;
        }
        List<String> allowedModules = new ArrayList<>(runtimeProfileService.allowedPythonModules(profile));
        artifact.put("allowedModules", allowedModules);
        RuleRuntimeClient.RuntimeResult result = ruleRuntimeClient.validate(artifact, profile);
        Map<String, Object> report = new LinkedHashMap<>(result.payload());
        report.put("executionTimeMs", result.process().executionTimeMs());
        report.put("runtimeProfileId", profile.getId());
        report.put("runtimeType", profile.getRuntimeType());
        report.put("imageDigest", profile.getImageDigest());
        report.put("dependencies", dependencies);
        report.put("allowedModules", allowedModules);
        if (result.successful()) {
            draft.setStatus("VALIDATED");
            report.put("valid", true);
        } else {
            draft.setStatus("VALIDATION_FAILED");
            report.put("valid", false);
            report.put("error", result.errorMessage());
        }
        draft.setValidationReport(json(report));
        ruleDraftMapper.updateById(draft);
        return draft;
    }

    private OperatorDefinition requireRuleOperator(Long operatorId) {
        OperatorDefinition definition = versionCatalogService.requireOperator(operatorId);
        if (!OperatorTypes.RULE.equals(definition.getOperatorType())) {
            throw new BusinessException(422, "只有RULE算子可以通过对话生成自定义规则工件");
        }
        return definition;
    }

    private RuleDraft requireDraft(Long operatorId, Long draftId) {
        RuleDraft draft = draftId == null ? null : ruleDraftMapper.selectById(draftId);
        if (draft == null || !operatorId.equals(draft.getOperatorId())) {
            throw new BusinessException(404, "规则草稿不存在: " + draftId);
        }
        if (!currentUserId().equals(draft.getCreatedByUserId())) {
            throw new BusinessException(403, "无权访问该规则草稿");
        }
        return draft;
    }

    private String conversationContext(Long conversationId) {
        if (conversationId == null) return "";
        List<Map<String, Object>> history = chatMessageMapper.selectMessagesByConversation(conversationId);
        int start = Math.max(0, history.size() - 20);
        StringBuilder result = new StringBuilder();
        for (int i = start; i < history.size() && result.length() < 20_000; i++) {
            Map<String, Object> item = history.get(i);
            result.append(item.get("role")).append(": ")
                .append(text(item.get("content"))).append('\n');
        }
        return result.toString();
    }

    private String authoringPrompt(String instruction, String history, Map<String, Object> previous,
                                   Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        return """
            根据对话和本次要求生成或修改一个自定义规则算子。规则不是固定原语拼装，可以实现任意业务判断，
            但必须是纯函数：禁止网络、文件、进程、数据库和系统调用；输入为 records 数组，输出仍为 records 数组；
            每条输出必须原样保留 __sourceRefs 和 __sourceSnapshots。入口函数签名为
            evaluate(records, parameters)，返回记录数组。至少给出正常和边界两个测试。

            只返回 JSON 对象，字段必须为：language(固定python)、entrypoint、sourceCode、inputSchema、
            outputSchema、parameterSchema、tests(数组，元素含name/input/parameters/expected)、explanation。

            对话上下文：
            %s
            本次要求：%s
            上一版本工件：%s
            期望输入Schema：%s
            期望输出Schema：%s
            """.formatted(history, instruction, json(previous), json(inputSchema), json(outputSchema));
    }

    private Map<String, Object> parseArtifact(String response) {
        if (response == null) throw new BusinessException(502, "LLM未返回规则工件");
        String json = response.trim();
        if (json.startsWith("```")) {
            int newline = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (newline >= 0 && end > newline) json = json.substring(newline + 1, end).trim();
        }
        return object(json, "LLM规则工件");
    }

    private void validateArtifact(Map<String, Object> artifact) {
        if (!"python".equalsIgnoreCase(required(artifact, "language"))) {
            throw new BusinessException(422, "规则工件language必须为python");
        }
        if (!required(artifact, "entrypoint").matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new BusinessException(422, "规则工件entrypoint格式不正确");
        }
        String source = required(artifact, "sourceCode");
        if (source.length() > 50_000) throw new BusinessException(422, "规则源码不能超过50000字符");
        if (!(artifact.get("tests") instanceof List<?> tests) || tests.size() < 2) {
            throw new BusinessException(422, "对话生成的规则至少需要正常和边界两个测试");
        }
    }

    private String required(Map<String, Object> map, String field) {
        String value = text(map == null ? null : map.get(field));
        if (value == null) throw new BusinessException(field + "不能为空");
        return value;
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException e) { throw new BusinessException("ID必须是整数"); }
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> value)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private Map<String, Object> object(String json, String name) {
        try { return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new BusinessException(422, name + "不是有效JSON对象"); }
    }

    private List<Map<String, Object>> parseList(String json) {
        try { return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { throw new BusinessException(422, "测试用例不是有效JSON数组"); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException("规则工件序列化失败: " + e.getMessage()); }
    }

    private String currentUserId() { return UserContextHolder.require().userId().toString(); }
}
