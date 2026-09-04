package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OutputDraft;
import com.smartquery.llm.LlmService;
import com.smartquery.mapper.ChatMessageMapper;
import com.smartquery.mapper.OutputDraftMapper;
import com.smartquery.orchestration.execution.LineageSupport;
import com.smartquery.orchestration.execution.OperatorExecutionContext;
import com.smartquery.orchestration.execution.OperatorExecutionResult;
import com.smartquery.orchestration.execution.OutputOperatorExecutor;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Dialogue -> declarative sandbox -> preview -> human approval workflow. */
@Service
@RequiredArgsConstructor
public class OutputAuthoringService {
    private final OutputDraftMapper outputDraftMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ResourceAccessService resourceAccessService;
    private final VersionCatalogService versionCatalogService;
    private final ContentHashService contentHashService;
    private final OutputSpecSandbox outputSpecSandbox;
    private final OutputCapabilityRegistryService outputCapabilityRegistryService;
    private final OutputOperatorExecutor outputOperatorExecutor;
    private final RuntimeProfileService runtimeProfileService;
    private final DependencyCenterService dependencyCenterService;
    private final OperatorApprovalService operatorApprovalService;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    @Value("${smart-query.llm.default-model:glm-5.1}")
    private String defaultModel;

    @Value("${smart-query.orchestration.output-preview.max-records:100}")
    private int maxPreviewRecords;

    @Value("${smart-query.orchestration.output-preview.max-input-bytes:524288}")
    private int maxPreviewBytes;

    public List<OutputDraft> list(Long operatorId) {
        requireOutputOperator(operatorId);
        return outputDraftMapper.selectList(new LambdaQueryWrapper<OutputDraft>()
            .eq(OutputDraft::getOperatorId, operatorId)
            .eq(OutputDraft::getCreatedByUserId, currentUserId())
            .orderByDesc(OutputDraft::getCreatedAt));
    }

    @Transactional
    public OutputDraft generate(Long operatorId, Map<String, Object> body) {
        requireOutputOperator(operatorId);
        String instruction = required(body, "instruction");
        Long conversationId = number(body.get("conversationId"));
        if (conversationId != null) resourceAccessService.requireConversation(conversationId);
        Long basedOnVersionId = number(body.get("basedOnVersionId"));
        Map<String, Object> previous = Map.of();
        if (basedOnVersionId != null) {
            OperatorVersion version = versionCatalogService.requireOperatorVersionVisible(basedOnVersionId);
            if (!operatorId.equals(version.getOperatorId())) {
                throw new BusinessException(422, "basedOnVersionId不属于当前输出算子");
            }
            previous = object(version.getImplementationPayload(), "已有输出工件");
        }
        Map<String, Object> requestedInputSchema = map(body.getOrDefault("inputSchema", Map.of()));
        List<String> selectedCapabilities = strings(body.get("selectedCapabilityCodes"));
        if (!selectedCapabilities.isEmpty()) {
            java.util.Set<String> enabled = outputCapabilityRegistryService.list(false).stream()
                .map(OutputCapabilityRegistryService.CapabilityView::code).collect(java.util.stream.Collectors.toSet());
            List<String> invalid = selectedCapabilities.stream().filter(code -> !enabled.contains(code)).toList();
            if (!invalid.isEmpty()) throw new BusinessException(422, "选择了未启用的输出能力: " + String.join(", ", invalid));
        }
        String prompt = authoringPrompt(instruction, conversationContext(conversationId), previous,
            requestedInputSchema, map(body.get("sampleFields")), selectedCapabilities);
        String model = text(body.get("model"));
        String response = llmService.chat(model == null ? defaultModel : model,
            List.of(Map.of("role", "system", "content", "你是数据产品的输出算子设计师，只返回严格JSON。"),
                Map.of("role", "user", "content", prompt)));
        Map<String, Object> artifact = parseArtifact(response);
        validateGenerated(artifact);

        Map<String, Object> rawSpec = new LinkedHashMap<>();
        if (artifact.get("targets") instanceof List<?>) {
            rawSpec.put("specVersion", 2);
            rawSpec.put("transformations", artifact.getOrDefault("transformations", List.of()));
            rawSpec.put("targets", artifact.get("targets"));
        } else {
            // Accept an older model response and let the sandbox migrate it to one V2 target.
            rawSpec.put("outputKind", String.valueOf(artifact.get("outputKind")).toUpperCase(Locale.ROOT));
            rawSpec.put("contentSpec", map(artifact.get("contentSpec")));
        }
        // Built-in output capabilities are pinned by the registry. The LLM cannot add packages.
        rawSpec.put("dependencies", List.of());
        OutputDraft draft = new OutputDraft();
        draft.setOperatorId(operatorId);
        draft.setConversationId(conversationId);
        draft.setBasedOnVersionId(basedOnVersionId);
        draft.setInstructionText(instruction);
        draft.setRawSpec(json(rawSpec));
        draft.setShapedSpec(json(Map.of()));
        draft.setInputSchema(json(artifact.getOrDefault("inputSchema", requestedInputSchema)));
        draft.setOutputSchema(json(artifact.getOrDefault("outputSchema", Map.of("type", "object"))));
        draft.setParameterSchema(json(artifact.getOrDefault("parameterSchema", Map.of())));
        draft.setExplanation(text(artifact.get("explanation")));
        draft.setStatus("GENERATED");
        draft.setShapingReport(json(Map.of("valid", false, "pending", true)));
        draft.setPreviewData(json(Map.of()));
        draft.setPreviewReport(json(Map.of("valid", false, "pending", true)));
        draft.setCreatedByUserId(currentUserId());
        outputDraftMapper.insert(draft);
        return draft;
    }

    /** Normalizes the LLM draft to the platform's non-executable rendering vocabulary. */
    @Transactional
    public OutputDraft shape(Long operatorId, Long draftId) {
        return shape(operatorId, draftId, null);
    }

    @Transactional
    public OutputDraft shape(Long operatorId, Long draftId, Long runtimeProfileId) {
        requireOutputOperator(operatorId);
        OutputDraft draft = requireDraft(operatorId, draftId);
        ensureNotPublished(draft);
        try {
            Map<String, Object> raw = object(draft.getRawSpec(), "rawSpec");
            OutputSpecSandbox.ShapeResult shaped = outputSpecSandbox.shape(
                raw);
            Map<String, Object> shapedSpec = new LinkedHashMap<>(shaped.spec());
            List<Map<String, Object>> dependencies = runtimeProfileService.requirements(raw);
            shapedSpec.put("dependencies", dependencies);
            com.smartquery.entity.RuntimeProfile profile = runtimeProfileService.resolveForVersion(
                OperatorTypes.OUTPUT, "OUTPUT_RENDERER", runtimeProfileId, shapedSpec);
            draft.setShapedSpec(json(shapedSpec));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("valid", true);
            report.put("contentHash", shaped.contentHash());
            report.put("renderer", shaped.renderer());
            report.put("warnings", shaped.warnings());
            report.put("sandbox", "COMPOSABLE_OUTPUT_V2");
            report.put("runtimeProfileId", profile.getId());
            report.put("runtimeType", profile.getRuntimeType());
            report.put("imageDigest", profile.getImageDigest());
            report.put("dependencies", dependencies);
            draft.setShapingReport(json(report));
            draft.setStatus("SHAPED");
            draft.setPreviewData(json(Map.of()));
            draft.setPreviewReport(json(Map.of("valid", false, "pending", true)));
        } catch (DependencyMissingException missing) {
            draft.setStatus("DEPENDENCY_MISSING");
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("valid", false);
            report.put("code", "DEPENDENCY_MISSING");
            report.put("runtimeType", missing.runtimeType());
            report.put("runtimeProfileId", missing.runtimeProfileId());
            report.put("missingDependencies", missing.missing());
            report.put("error", missing.getMessage());
            draft.setShapingReport(json(report));
            dependencyCenterService.markDraftMissing("OUTPUT", draft.getId(), missing.missing());
        } catch (RuntimeException error) {
            draft.setStatus("SHAPING_FAILED");
            draft.setShapingReport(json(Map.of("valid", false, "error", errorText(error),
                "sandbox", "COMPOSABLE_OUTPUT_V2")));
        }
        outputDraftMapper.updateById(draft);
        return draft;
    }

    /** Executes the shaped spec against bounded sample data and returns the exact UI view contract. */
    @Transactional
    public PreviewResult preview(Long operatorId, Long draftId, Map<String, Object> body) {
        requireOutputOperator(operatorId);
        OutputDraft draft = requireDraft(operatorId, draftId);
        ensureNotPublished(draft);
        if (!List.of("SHAPED", "PREVIEW_VALIDATED", "PREVIEW_FAILED").contains(draft.getStatus())) {
            throw new BusinessException(422, "输出草稿必须先通过沙箱整形");
        }
        Map<String, Object> shapingReport = object(draft.getShapingReport(), "shapingReport");
        Long runtimeProfileId = number(shapingReport.get("runtimeProfileId"));
        Map<String, Object> shapedSpec = object(draft.getShapedSpec(), "shapedSpec");
        com.smartquery.entity.RuntimeProfile profile = runtimeProfileService.resolveForVersion(
            OperatorTypes.OUTPUT, "OUTPUT_RENDERER", runtimeProfileId, shapedSpec);
        List<Map<String, Object>> rawRecords = records(body.get("records"));
        validatePreviewInput(body, rawRecords);
        Map<String, Object> preview = Map.of();
        Map<String, Object> report;
        try {
            List<Map<String, Object>> enriched = LineageSupport.enrich(-draft.getId(), rawRecords);
            Map<String, Object> shaped = shapedSpec;
            OperatorExecutionResult upstream = new OperatorExecutionResult(
                Map.of("records", enriched), List.of(), "output draft preview");
            OperatorExecutionContext context = new OperatorExecutionContext(
                -draft.getId(), -draft.getId(), "output-preview", OperatorTypes.OUTPUT,
                new OperatorVersion(), shaped, Map.of(), Map.of(), Map.of("sample", upstream));
            OperatorExecutionResult executed = outputOperatorExecutor.execute(context);
            LineageSupport.requirePreserved(executed.output(), "output-preview");
            List<Map<String, Object>> outputRecords = records(executed.output().get("records"));
            OutputSpecSandbox.PreviewValidation validation = outputSpecSandbox.validatePreview(shaped, outputRecords);
            report = new LinkedHashMap<>();
            report.put("valid", validation.valid());
            report.put("errors", validation.errors());
            report.put("warnings", validation.warnings());
            report.put("recordCount", outputRecords.size());
            report.put("leadCount", executed.leads().size());
            report.put("previewHash", contentHashService.sha256(executed.output()));
            report.put("runtimeProfileId", profile.getId());
            report.put("imageDigest", profile.getImageDigest());
            if (validation.valid()) {
                preview = previewModel(shaped, executed, outputRecords);
                draft.setStatus("PREVIEW_VALIDATED");
                draft.setPreviewData(persistedPreview(preview));
            } else {
                draft.setStatus("PREVIEW_FAILED");
                draft.setPreviewData(json(Map.of()));
            }
        } catch (RuntimeException error) {
            draft.setStatus("PREVIEW_FAILED");
            draft.setPreviewData(json(Map.of()));
            report = Map.of("valid", false, "errors", List.of(errorText(error)),
                "warnings", List.of());
        }
        draft.setPreviewReport(json(report));
        outputDraftMapper.updateById(draft);
        return new PreviewResult(draft, preview, report);
    }

    /** Creates an immutable output version and submits it for independent human approval. */
    @Transactional
    public OperatorVersion publish(Long operatorId, Long draftId) {
        requireOutputOperator(operatorId);
        OutputDraft draft = requireDraft(operatorId, draftId);
        if ("PUBLISHED".equals(draft.getStatus()) && draft.getPublishedVersionId() != null) {
            return versionCatalogService.requireOperatorVersionVisible(draft.getPublishedVersionId());
        }
        if ("PENDING_APPROVAL".equals(draft.getStatus()) && draft.getCandidateVersionId() != null) {
            return versionCatalogService.requireOperatorVersionVisible(draft.getCandidateVersionId());
        }
        if (!"PREVIEW_VALIDATED".equals(draft.getStatus())) {
            throw new BusinessException(422, "输出草稿必须先通过可视化预览验证才能发布");
        }
        Map<String, Object> payload = new LinkedHashMap<>(object(draft.getShapedSpec(), "shapedSpec"));
        payload.put("draftId", draft.getId());
        payload.put("conversationId", draft.getConversationId());
        payload.put("sandboxShaped", true);
        payload.put("previewValidated", true);
        payload.put("shapingReport", object(draft.getShapingReport(), "shapingReport"));
        payload.put("previewReport", object(draft.getPreviewReport(), "previewReport"));
        Map<String, Object> versionBody = new LinkedHashMap<>();
        versionBody.put("inputSchema", object(draft.getInputSchema(), "inputSchema"));
        versionBody.put("outputSchema", object(draft.getOutputSchema(), "outputSchema"));
        versionBody.put("parameterSchema", object(draft.getParameterSchema(), "parameterSchema"));
        versionBody.put("implementationType", "OUTPUT_RENDERER");
        versionBody.put("implementationPayload", payload);
        versionBody.put("runtimeProfileId", object(draft.getShapingReport(), "shapingReport")
            .get("runtimeProfileId"));
        OperatorVersion candidate = versionCatalogService.createOperatorVersion(operatorId, versionBody);
        operatorApprovalService.submitFromDraft(operatorId, candidate.getId(), "OUTPUT", draft.getId(),
            "输出草稿已通过声明式整形和可视化预览");
        if (!VersionStatus.PUBLISHED.equals(candidate.getStatus())) candidate.setStatus(VersionStatus.PENDING_APPROVAL);
        draft.setCandidateVersionId(candidate.getId());
        if (VersionStatus.PUBLISHED.equals(candidate.getStatus())) {
            draft.setPublishedVersionId(candidate.getId());
            draft.setStatus("PUBLISHED");
        } else {
            draft.setStatus("PENDING_APPROVAL");
        }
        outputDraftMapper.updateById(draft);
        return candidate;
    }

    private Map<String, Object> previewModel(Map<String, Object> shaped, OperatorExecutionResult executed,
                                             List<Map<String, Object>> records) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            rows.add(outputSpecSandbox.viewRow(records.get(index), index));
        }
        if ("2".equals(String.valueOf(shaped.get("specVersion")))) {
            List<Map<String, Object>> views = new ArrayList<>();
            for (Map<String, Object> artifact : maps(executed.output().get("artifacts"))) {
                Map<String, Object> spec = map(artifact.get("contentSpec"));
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("targetId", artifact.get("targetId"));
                view.put("capabilityCode", artifact.get("capabilityCode"));
                view.put("capabilityType", artifact.get("capabilityType"));
                view.put("outputKind", artifact.get("kind"));
                view.put("contentSpec", spec);
                view.put("renderer", artifact.get("renderer"));
                view.put("recordCount", records.size());
                view.put("columns", spec.getOrDefault("columns", List.of()));
                view.put("rows", rows);
                views.add(Map.copyOf(view));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("specVersion", 2);
            result.put("recordCount", records.size());
            result.put("leadCount", executed.leads().size());
            result.put("targetViews", List.copyOf(views));
            if (!views.isEmpty()) {
                Map<String, Object> first = views.get(0);
                result.put("outputKind", first.get("outputKind"));
                result.put("contentSpec", first.get("contentSpec"));
                result.put("renderer", first.get("renderer"));
                result.put("columns", first.get("columns"));
                result.put("rows", rows);
            }
            return result;
        }
        Map<String, Object> spec = map(shaped.get("contentSpec"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outputKind", shaped.get("outputKind"));
        result.put("contentSpec", spec);
        result.put("renderer", map(executed.output().get("artifact")).get("renderer"));
        result.put("recordCount", records.size());
        result.put("leadCount", executed.leads().size());
        result.put("columns", spec.getOrDefault("columns", List.of()));
        result.put("rows", rows);
        return result;
    }

    /** Keeps the draft row below portable TEXT limits while the API still returns the full live preview. */
    private String persistedPreview(Map<String, Object> preview) {
        Map<String, Object> stored = new LinkedHashMap<>(preview);
        Object rawRows = stored.remove("rows");
        List<Object> accepted = new ArrayList<>();
        stored.put("rows", accepted);
        if (rawRows instanceof List<?> rows) {
            for (Object row : rows) {
                accepted.add(row);
                if (json(stored).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 55_000) {
                    accepted.remove(accepted.size() - 1);
                    stored.put("previewTruncated", true);
                    break;
                }
            }
            stored.put("previewRows", accepted.size());
        }
        return json(stored);
    }

    private void validatePreviewInput(Map<String, Object> body, List<Map<String, Object>> records) {
        if (records.isEmpty()) throw new BusinessException(422, "预览至少需要一条records样例");
        if (records.size() > maxPreviewRecords) throw new BusinessException(413, "预览最多" + maxPreviewRecords + "条记录");
        for (Map<String, Object> record : records) {
            if (record.keySet().stream().anyMatch(key -> key.startsWith("__"))) {
                throw new BusinessException(422, "预览输入不能伪造平台血缘字段");
            }
        }
        try {
            if (objectMapper.writeValueAsBytes(body).length > maxPreviewBytes) {
                throw new BusinessException(413, "预览输入超过大小限制");
            }
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(422, "预览输入无法序列化"); }
    }

    private OperatorDefinition requireOutputOperator(Long operatorId) {
        OperatorDefinition definition = versionCatalogService.requireOperator(operatorId);
        if (!OperatorTypes.OUTPUT.equals(definition.getOperatorType())) {
            throw new BusinessException(422, "只有OUTPUT算子可以使用输出草稿发布流程");
        }
        return definition;
    }

    private OutputDraft requireDraft(Long operatorId, Long draftId) {
        OutputDraft draft = draftId == null ? null : outputDraftMapper.selectById(draftId);
        if (draft == null || !operatorId.equals(draft.getOperatorId())) {
            throw new BusinessException(404, "输出草稿不存在: " + draftId);
        }
        if (!currentUserId().equals(draft.getCreatedByUserId())) {
            throw new BusinessException(403, "无权访问该输出草稿");
        }
        return draft;
    }

    private void ensureNotPublished(OutputDraft draft) {
        if (List.of("PUBLISHED", "PENDING_APPROVAL", "APPROVAL_REJECTED").contains(draft.getStatus())) {
            throw new BusinessException(409, "已提交审批的草稿不可修改，请通过对话生成新草稿");
        }
    }

    private String authoringPrompt(String instruction, String history, Map<String, Object> previous,
                                   Map<String, Object> inputSchema, Map<String, Object> sampleFields,
                                   List<String> selectedCapabilities) {
        List<Map<String, Object>> capabilities = outputCapabilityRegistryService.list(false).stream()
            .map(item -> Map.<String, Object>of("code", item.code(), "type", item.capabilityType(),
                "name", item.name(), "implementationType", item.implementationType()))
            .toList();
        return """
            根据对话生成或修改一个“可组合结果出口”草稿。安全、权限、审计和血缘由平台强制执行。
            转换可选，输出目标至少一个；展示、持久化、导出和业务动作彼此独立并可同时选择多个。
            不得包含代码、SQL、HTML、URL、模板、formatter、任意ECharts option或dependencies。

            只返回JSON对象，字段必须为：specVersion（固定2）、transformations、targets、inputSchema、
            outputSchema、parameterSchema、explanation。每个transformations/targets元素只包含id、
            capabilityCode、config；capabilityCode只能来自平台目录。表格和导出的columns元素使用
            field/title/format/width；图表config使用chartType、dimensions、measures；组合页面使用
            metric/chart/table/filter/container可信widgets；线索使用leadPolicy。排序使用sort数组，元素仅
            field和direction（asc/desc）。展示与导出不要相互替代。

            当前已启用的数据库能力目录：%s
            用户明确选择的能力（非空时必须全部使用，仍可按指令补充其他目标）：%s

            对话上下文：
            %s
            本次要求：%s
            上一版本工件：%s
            输入Schema：%s
            可用样例字段：%s
            """.formatted(json(capabilities), json(selectedCapabilities), history, instruction,
                json(previous), json(inputSchema), json(sampleFields));
    }

    private String conversationContext(Long conversationId) {
        if (conversationId == null) return "";
        List<Map<String, Object>> history = chatMessageMapper.selectMessagesByConversation(conversationId);
        int start = Math.max(0, history.size() - 20);
        StringBuilder result = new StringBuilder();
        for (int index = start; index < history.size() && result.length() < 20_000; index++) {
            Map<String, Object> item = history.get(index);
            result.append(item.get("role")).append(": ").append(text(item.get("content"))).append('\n');
        }
        return result.toString();
    }

    private Map<String, Object> parseArtifact(String response) {
        if (response == null) throw new BusinessException(502, "LLM未返回输出工件");
        String value = response.trim();
        if (value.startsWith("```")) {
            int newline = value.indexOf('\n');
            int end = value.lastIndexOf("```");
            if (newline >= 0 && end > newline) value = value.substring(newline + 1, end).trim();
        }
        return object(value, "LLM输出工件");
    }

    private void validateGenerated(Map<String, Object> artifact) {
        if (artifact.get("targets") instanceof List<?> targets) {
            if (targets.isEmpty()) throw new BusinessException(422, "LLM输出工件至少需要一个target");
            if (targets.size() > 20) throw new BusinessException(422, "LLM输出目标不能超过20个");
            if (artifact.get("transformations") != null && !(artifact.get("transformations") instanceof List<?>)) {
                throw new BusinessException(422, "LLM输出工件transformations必须是数组");
            }
        } else {
            String kind = required(artifact, "outputKind").toUpperCase(Locale.ROOT);
            if (!List.of("LEAD", "CHART", "TABLE", "EXCEL").contains(kind)) {
                throw new BusinessException(422, "LLM输出工件类型不受支持");
            }
            if (!(artifact.get("contentSpec") instanceof Map<?, ?>)) {
                throw new BusinessException(422, "LLM输出工件contentSpec必须是对象");
            }
        }
        if (json(artifact).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 50_000) {
            throw new BusinessException(422, "LLM输出工件过大");
        }
    }

    private List<Map<String, Object>> records(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?>)) throw new BusinessException(422, "records元素必须是对象");
            result.add(map(value));
        }
        return result;
    }

    private String required(Map<String, Object> map, String field) {
        String value = text(map == null ? null : map.get(field));
        if (value == null) throw new BusinessException(field + "不能为空");
        return value;
    }
    private Long number(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(raw)); }
        catch (Exception e) { throw new BusinessException(422, "ID必须是整数"); }
    }
    private String text(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
    private Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
    private List<Map<String, Object>> maps(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list) if (value instanceof Map<?, ?>) result.add(map(value));
        return result;
    }
    private List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).map(String::trim).filter(value -> !value.isEmpty())
            .distinct().toList();
    }
    private Map<String, Object> object(String value, String name) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new BusinessException(422, name + "不是有效JSON对象"); }
    }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException("输出工件序列化失败: " + e.getMessage()); }
    }
    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
    private String errorText(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return limit(value == null ? error.getClass().getSimpleName() : value, 2000);
    }
    private String currentUserId() { return UserContextHolder.require().userId().toString(); }

    public record PreviewResult(OutputDraft draft, Map<String, Object> preview,
                                Map<String, Object> validationReport) {}
}
