package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.service.RoleService;
import com.smartquery.entity.FlowDefinition;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.mapper.FlowDefinitionMapper;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.OperatorDefinitionMapper;
import com.smartquery.mapper.OperatorVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creates immutable operator and flow snapshots. Definition metadata may evolve,
 * but a version row is never updated in place.
 */
@Service
@RequiredArgsConstructor
public class VersionCatalogService {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_-]{2,99}$");
    private static final Map<String, Set<String>> ALLOWED_IMPLEMENTATIONS = Map.of(
        OperatorTypes.DATA, Set.of("SQL_AST", "BUILTIN"),
        OperatorTypes.RULE, Set.of("RULE_DSL", "SANDBOX_EXTENSION"),
        OperatorTypes.ML, Set.of("MINING_RUNTIME"),
        OperatorTypes.AGENT, Set.of("AGENT_POLICY"),
        OperatorTypes.OUTPUT, Set.of("OUTPUT_RENDERER")
    );

    private final OperatorDefinitionMapper operatorDefinitionMapper;
    private final OperatorVersionMapper operatorVersionMapper;
    private final FlowDefinitionMapper flowDefinitionMapper;
    private final FlowVersionMapper flowVersionMapper;
    private final RuleCompositionService ruleCompositionService;
    private final DagValidationService dagValidationService;
    private final SchemaCompatibilityService schemaCompatibilityService;
    private final SqlAstPolicyService sqlAstPolicyService;
    private final AgentPolicyService agentPolicyService;
    private final ContentHashService contentHashService;
    private final RuntimeProfileService runtimeProfileService;
    private final OutputCapabilityRegistryService outputCapabilityRegistryService;
    private final ObjectMapper objectMapper;
    private final RoleService roleService;

    public List<OperatorDefinition> listOperators() {
        LambdaQueryWrapper<OperatorDefinition> query = new LambdaQueryWrapper<OperatorDefinition>()
            .eq(OperatorDefinition::getDeleted, 0)
            .orderByDesc(OperatorDefinition::getCreatedAt);
        if (!isAdmin()) query.and(scope -> scope
            .eq(OperatorDefinition::getOwnerUserId, currentUserId())
            .or().eq(OperatorDefinition::getOwnerUserId, "SYSTEM"));
        return operatorDefinitionMapper.selectList(query);
    }

    @Transactional
    public OperatorDefinition createOperator(Map<String, Object> body) {
        String code = requiredText(body, "code").toLowerCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new BusinessException("算子code必须以小写字母开头，只能包含小写字母、数字、_和-");
        }
        String operatorType = requiredText(body, "operatorType").toUpperCase(Locale.ROOT);
        if (!OperatorTypes.ALL.contains(operatorType)) {
            throw new BusinessException("不支持的算子类型: " + operatorType);
        }
        Long existing = operatorDefinitionMapper.selectCount(new LambdaQueryWrapper<OperatorDefinition>()
            .eq(OperatorDefinition::getCode, code)
            .eq(OperatorDefinition::getOwnerUserId, currentUserId())
            .eq(OperatorDefinition::getDeleted, 0));
        if (existing != null && existing > 0) throw new BusinessException(409, "算子code已存在: " + code);

        OperatorDefinition definition = new OperatorDefinition();
        definition.setCode(code);
        definition.setName(requiredText(body, "name"));
        definition.setDescription(text(body.get("description")));
        definition.setOperatorType(operatorType);
        definition.setOwnerUserId(currentUserId());
        definition.setStatus("ACTIVE");
        definition.setDeleted(0);
        operatorDefinitionMapper.insert(definition);
        return definition;
    }

    @Transactional
    public OperatorDefinition updateOperatorMetadata(Long operatorId, String name, String description) {
        OperatorDefinition definition = requireOperator(operatorId);
        if (name == null || name.isBlank()) throw new BusinessException("算子名称不能为空");
        definition.setName(name.trim());
        definition.setDescription(description == null ? "" : description.trim());
        operatorDefinitionMapper.updateById(definition);
        return definition;
    }

    @Transactional
    public void archiveOperator(Long operatorId) {
        OperatorDefinition definition = requireOperator(operatorId);
        definition.setStatus("ARCHIVED");
        definition.setDeleted(1);
        operatorDefinitionMapper.updateById(definition);
    }

    public List<OperatorVersion> listOperatorVersions(Long operatorId) {
        requireOperatorReadable(operatorId);
        return operatorVersionMapper.selectList(new LambdaQueryWrapper<OperatorVersion>()
            .eq(OperatorVersion::getOperatorId, operatorId)
            .orderByDesc(OperatorVersion::getVersionNo));
    }

    /** Read-only palette projection. It intentionally omits executable payload/source code. */
    public List<PublishedOperatorView> listPublishedOperatorCatalog(String requestedType) {
        String operatorType = text(requestedType);
        if (operatorType != null) {
            operatorType = operatorType.toUpperCase(Locale.ROOT);
            if (!OperatorTypes.ALL.contains(operatorType)) {
                throw new BusinessException(422, "不支持的算子类型: " + operatorType);
            }
        }
        String selectedType = operatorType;
        List<PublishedOperatorView> result = new ArrayList<>();
        for (OperatorDefinition definition : listOperators()) {
            if (!"ACTIVE".equals(definition.getStatus())) continue;
            if (selectedType != null && !selectedType.equals(definition.getOperatorType())) continue;
            List<OperatorVersion> versions = operatorVersionMapper.selectList(
                new LambdaQueryWrapper<OperatorVersion>()
                    .eq(OperatorVersion::getOperatorId, definition.getId())
                    .eq(OperatorVersion::getStatus, VersionStatus.PUBLISHED)
                    .orderByDesc(OperatorVersion::getVersionNo));
            for (OperatorVersion version : versions) {
                RuntimeProfileService.RuntimeBindingView runtime = runtimeProfileService.binding(version.getId());
                result.add(new PublishedOperatorView(
                    definition.getId(), version.getId(), definition.getCode(), definition.getName(),
                    definition.getDescription(), definition.getOperatorType(), version.getVersionNo(),
                    version.getImplementationType(), version.getInputSchema(), version.getOutputSchema(),
                    version.getParameterSchema(), runtime.profile().getId(), runtime.profile().getCode(),
                    runtime.binding().getImageDigest(), paletteMetadata(definition, version)));
            }
        }
        return List.copyOf(result);
    }

    @Transactional
    public synchronized OperatorVersion createOperatorVersion(Long operatorId, Map<String, Object> body) {
        OperatorDefinition definition = requireOperator(operatorId);
        Object inputSchema = object(body.getOrDefault("inputSchema", Map.of()), "inputSchema");
        Object outputSchema = object(body.getOrDefault("outputSchema", Map.of()), "outputSchema");
        Object parameterSchema = object(body.getOrDefault("parameterSchema", Map.of()), "parameterSchema");
        String implementationType = requiredText(body, "implementationType").toUpperCase(Locale.ROOT);
        if (!ALLOWED_IMPLEMENTATIONS.getOrDefault(definition.getOperatorType(), Set.of())
                .contains(implementationType)) {
            throw new BusinessException("算子类型" + definition.getOperatorType()
                + "不允许实现方式" + implementationType + "，允许值: "
                + ALLOWED_IMPLEMENTATIONS.getOrDefault(definition.getOperatorType(), Set.of()));
        }
        Object implementationPayload = body.get("implementationPayload");
        if (implementationPayload == null) throw new BusinessException("implementationPayload不能为空");
        Map<String, Object> payloadObject = object(implementationPayload, "implementationPayload");

        Object validationReport = Map.of("valid", true, "errors", List.of(), "warnings", List.of());
        List<String> requirements = new ArrayList<>();
        if (OperatorTypes.RULE.equals(definition.getOperatorType()) && "RULE_DSL".equals(implementationType)) {
            Map<String, Object> composition = payloadObject;
            RuleCompositionService.RuleValidationReport report = ruleCompositionService.validate(composition);
            validationReport = report;
            requirements.addAll(compositionSteps(composition));
            if (!report.supported()) {
                throw new BusinessException(422, "规则组合存在能力缺口或参数错误: "
                    + String.join("；", report.errors()));
            }
        } else if (OperatorTypes.RULE.equals(definition.getOperatorType())
                && "SANDBOX_EXTENSION".equals(implementationType)) {
            Map<String, Object> artifact = payloadObject;
            validateRuleArtifact(artifact);
            validationReport = Map.of("valid", true, "executionMode", "ISOLATED_SANDBOX",
                "warnings", List.of("候选版本必须通过沙箱测试后才能发布"));
        } else if (OperatorTypes.ML.equals(definition.getOperatorType())) {
            Map<String, Object> modelSpec = payloadObject;
            validateMiningRuntime(modelSpec);
            validationReport = Map.of("valid", true, "modelId", modelSpec.get("modelId"),
                "executionMode", "TRANSIENT_PREDICTION");
        } else if (OperatorTypes.DATA.equals(definition.getOperatorType())
                && "SQL_AST".equals(implementationType)) {
            SqlAstPolicyService.SqlAstSpec sqlSpec = sqlAstPolicyService.validate(payloadObject);
            requirements.add("datasource:" + sqlSpec.dataSourceId());
            sqlSpec.usedTables().stream().sorted().map(table -> "table:" + table).forEach(requirements::add);
            validationReport = Map.of("valid", true, "executionMode", "AUTHORIZED_SELECT",
                "dataSourceId", sqlSpec.dataSourceId(), "tables", sqlSpec.usedTables(),
                "maxRows", sqlSpec.maxRows(), "timeoutSeconds", sqlSpec.timeoutSeconds());
        } else if (OperatorTypes.AGENT.equals(definition.getOperatorType())) {
            AgentPolicyService.AgentPolicySpec agentSpec = agentPolicyService.validate(payloadObject);
            agentSpec.allowedTools().stream().map(tool -> "tool:" + tool).forEach(requirements::add);
            validationReport = Map.of("valid", true, "executionMode", "BOUNDED_READ_ONLY_AGENT",
                "model", agentSpec.model(), "allowedTools", agentSpec.allowedTools(),
                "maxTurns", agentSpec.maxTurns(), "maxToolCalls", agentSpec.maxToolCalls(),
                "maxInputRecords", agentSpec.maxInputRecords(), "maxTotalTokens", agentSpec.maxTotalTokens());
        } else if (OperatorTypes.OUTPUT.equals(definition.getOperatorType())) {
            Map<String, Object> outputSpec = payloadObject;
            validateOutputSpec(outputSpec);
            List<Map<String, Object>> outputTargets = listOfMapsOptional(outputSpec.get("targets"));
            if (outputTargets.isEmpty()) {
                validationReport = Map.of("valid", true, "specVersion", 1,
                    "outputKind", outputSpec.get("outputKind"));
            } else {
                List<String> capabilityCodes = outputTargets.stream()
                    .map(target -> String.valueOf(target.get("capabilityCode"))).toList();
                capabilityCodes.stream().map(code -> "output-capability:" + code).forEach(requirements::add);
                validationReport = Map.of("valid", true, "specVersion", 2,
                    "targetCount", outputTargets.size(), "capabilityCodes", capabilityCodes);
            }
        }

        Long requestedRuntimeProfileId = DagValidationService.toLong(body.get("runtimeProfileId"));
        com.smartquery.entity.RuntimeProfile runtimeProfile = runtimeProfileService.resolveForVersion(
            definition.getOperatorType(), implementationType, requestedRuntimeProfileId, payloadObject);
        Map<String, Object> versionValidation = new LinkedHashMap<>();
        versionValidation.put("artifactValidation", validationReport);
        versionValidation.put("runtimeProfileId", runtimeProfile.getId());
        versionValidation.put("runtimeType", runtimeProfile.getRuntimeType());
        versionValidation.put("imageDigest", runtimeProfile.getImageDigest());
        validationReport = versionValidation;

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("operatorId", operatorId);
        snapshot.put("operatorType", definition.getOperatorType());
        snapshot.put("inputSchema", inputSchema);
        snapshot.put("outputSchema", outputSchema);
        snapshot.put("parameterSchema", parameterSchema);
        snapshot.put("implementationType", implementationType);
        snapshot.put("implementationPayload", implementationPayload);
        snapshot.put("capabilityRequirements", requirements);
        snapshot.put("runtimeProfileId", runtimeProfile.getId());
        snapshot.put("runtimeImageDigest", runtimeProfile.getImageDigest());
        String hash = contentHashService.sha256(snapshot);

        OperatorVersion existing = operatorVersionMapper.selectOne(new LambdaQueryWrapper<OperatorVersion>()
            .eq(OperatorVersion::getOperatorId, operatorId)
            .eq(OperatorVersion::getContentHash, hash)
            .last("LIMIT 1"));
        if (existing != null) {
            runtimeProfileService.bind(existing.getId(), runtimeProfile);
            return existing;
        }

        OperatorVersion latest = operatorVersionMapper.selectOne(new LambdaQueryWrapper<OperatorVersion>()
            .eq(OperatorVersion::getOperatorId, operatorId)
            .orderByDesc(OperatorVersion::getVersionNo)
            .last("LIMIT 1"));
        OperatorVersion version = new OperatorVersion();
        version.setOperatorId(operatorId);
        version.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
        version.setStatus(VersionStatus.CANDIDATE);
        version.setContentHash(hash);
        version.setInputSchema(json(inputSchema));
        version.setOutputSchema(json(outputSchema));
        version.setParameterSchema(json(parameterSchema));
        version.setImplementationType(implementationType);
        version.setImplementationPayload(json(implementationPayload));
        version.setCapabilityRequirements(json(requirements));
        version.setValidationReport(json(validationReport));
        version.setCreatedByUserId(currentUserId());
        operatorVersionMapper.insert(version);
        runtimeProfileService.bind(version.getId(), runtimeProfile);
        return version;
    }

    public List<FlowDefinition> listFlows() {
        LambdaQueryWrapper<FlowDefinition> query = new LambdaQueryWrapper<FlowDefinition>()
            .eq(FlowDefinition::getDeleted, 0)
            .orderByDesc(FlowDefinition::getCreatedAt);
        if (!isAdmin()) query.eq(FlowDefinition::getOwnerUserId, currentUserId());
        return flowDefinitionMapper.selectList(query);
    }

    @Transactional
    public FlowDefinition createFlow(Map<String, Object> body) {
        String code = requiredText(body, "code").toLowerCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) throw new BusinessException("流程code格式不正确");
        Long existing = flowDefinitionMapper.selectCount(new LambdaQueryWrapper<FlowDefinition>()
            .eq(FlowDefinition::getCode, code)
            .eq(FlowDefinition::getOwnerUserId, currentUserId())
            .eq(FlowDefinition::getDeleted, 0));
        if (existing != null && existing > 0) throw new BusinessException(409, "流程code已存在: " + code);
        FlowDefinition definition = new FlowDefinition();
        definition.setCode(code);
        definition.setName(requiredText(body, "name"));
        definition.setDescription(text(body.get("description")));
        definition.setOwnerUserId(currentUserId());
        definition.setStatus("ACTIVE");
        definition.setDeleted(0);
        flowDefinitionMapper.insert(definition);
        return definition;
    }

    public List<FlowVersion> listFlowVersions(Long flowId) {
        requireFlow(flowId);
        return flowVersionMapper.selectList(new LambdaQueryWrapper<FlowVersion>()
            .eq(FlowVersion::getFlowId, flowId)
            .orderByDesc(FlowVersion::getVersionNo));
    }

    /** Combines structural DAG checks with exact-version record Schema checks for every edge. */
    public FlowValidationReport validateFlowDraft(List<Map<String, Object>> nodes,
                                                  List<Map<String, Object>> edges) {
        DagValidationService.DagValidationReport dag = dagValidationService.validate(nodes, edges);
        List<String> errors = new ArrayList<>(dag.errors());
        List<String> warnings = new ArrayList<>(dag.warnings());
        Map<String, OperatorVersion> versionsByNode = new LinkedHashMap<>();
        if (nodes != null) {
            for (Map<String, Object> node : nodes) {
                String nodeId = node == null ? null : text(node.get("id"));
                Long versionId = node == null ? null : DagValidationService.toLong(node.get("operatorVersionId"));
                if (nodeId == null || versionId == null) continue;
                try {
                    Map<String, Object> config = object(node.getOrDefault("config", Map.of()), "节点config");
                    errors.addAll(nodeTimeoutErrors(nodeId, config));
                } catch (BusinessException e) {
                    errors.add("节点[" + nodeId + "]: " + e.getMessage());
                }
                try {
                    versionsByNode.put(nodeId, requireOperatorVersionVisible(versionId));
                } catch (BusinessException e) {
                    errors.add("节点[" + nodeId + "]: " + e.getMessage());
                }
            }
        }
        List<SchemaCompatibilityService.EdgeContractReport> contracts = new ArrayList<>();
        if (edges != null) {
            for (Map<String, Object> edge : edges) {
                if (edge == null) continue;
                String source = text(edge.get("source"));
                String target = text(edge.get("target"));
                OperatorVersion sourceVersion = versionsByNode.get(source);
                OperatorVersion targetVersion = versionsByNode.get(target);
                if (source == null || target == null || sourceVersion == null || targetVersion == null) continue;
                SchemaCompatibilityService.EdgeContractReport contract = schemaCompatibilityService.validate(
                    source, sourceVersion, target, targetVersion, edge);
                contracts.add(contract);
                contract.errors().forEach(message -> errors.add("边[" + source + " -> " + target + "]: " + message));
                contract.warnings().forEach(message -> warnings.add("边[" + source + " -> " + target + "]: " + message));
            }
        }
        return new FlowValidationReport(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings),
            dag.topologicalOrder(), dag.executionLevels(), List.copyOf(contracts));
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public synchronized FlowVersion createFlowVersion(Long flowId, Map<String, Object> body) {
        requireFlow(flowId);
        List<Map<String, Object>> nodes = listOfMaps(body.get("nodes"), "nodes");
        List<Map<String, Object>> edges = listOfMaps(body.getOrDefault("edges", List.of()), "edges");
        Object parameterMappings = object(body.getOrDefault("parameterMappings", Map.of()), "parameterMappings");
        FlowValidationReport validation = validateFlowDraft(nodes, edges);
        List<String> errors = new ArrayList<>(validation.errors());
        Set<String> outputNodeIds = new java.util.LinkedHashSet<>();
        Set<String> sourceOnlyNodeIds = new java.util.LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            Long versionId = DagValidationService.toLong(node.get("operatorVersionId"));
            if (versionId != null) {
                try {
                    OperatorVersion version = requireOperatorVersionVisible(versionId);
                    OperatorDefinition operator = operatorDefinitionMapper.selectById(version.getOperatorId());
                    String nodeId = String.valueOf(node.get("id"));
                    Map<String, Object> config = object(node.getOrDefault("config", Map.of()), "节点config");
                    if (operator != null && OperatorTypes.DATA.equals(operator.getOperatorType())
                            && "SQL_AST".equals(version.getImplementationType())) {
                        sourceOnlyNodeIds.add(nodeId);
                        sqlAstPolicyService.validateNodeConfig(config);
                    }
                    if (operator != null && OperatorTypes.AGENT.equals(operator.getOperatorType())) {
                        agentPolicyService.validateNodeConfig(config);
                    }
                    if (operator != null && OperatorTypes.OUTPUT.equals(operator.getOperatorType())) {
                        String outputNodeId = nodeId;
                        outputNodeIds.add(outputNodeId);
                        Map<String, Object> payload = object(version.getImplementationPayload(), "输出算子工件");
                        errors.addAll(outputNodeErrors(outputNodeId, version.getStatus(),
                            payload.containsKey("draftId"), config));
                    }
                }
                catch (BusinessException e) { errors.add("节点[" + node.get("id") + "]: " + e.getMessage()); }
            }
        }
        errors.addAll(outputTerminalErrors(outputNodeIds, edges));
        errors.addAll(sourceRootErrors(sourceOnlyNodeIds, edges));
        if (!errors.isEmpty()) throw new BusinessException(422, "DAG预检失败: " + String.join("；", errors));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("flowId", flowId);
        snapshot.put("nodes", nodes);
        snapshot.put("edges", edges);
        snapshot.put("parameterMappings", parameterMappings);
        String hash = contentHashService.sha256(snapshot);
        FlowVersion existing = flowVersionMapper.selectOne(new LambdaQueryWrapper<FlowVersion>()
            .eq(FlowVersion::getFlowId, flowId)
            .eq(FlowVersion::getContentHash, hash)
            .last("LIMIT 1"));
        if (existing != null) return existing;

        FlowVersion latest = flowVersionMapper.selectOne(new LambdaQueryWrapper<FlowVersion>()
            .eq(FlowVersion::getFlowId, flowId)
            .orderByDesc(FlowVersion::getVersionNo)
            .last("LIMIT 1"));
        FlowVersion version = new FlowVersion();
        version.setFlowId(flowId);
        version.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
        version.setStatus(VersionStatus.CANDIDATE);
        version.setContentHash(hash);
        version.setNodes(json(nodes));
        version.setEdges(json(edges));
        version.setParameterMappings(json(parameterMappings));
        version.setValidationReport(json(validation));
        version.setCreatedByUserId(currentUserId());
        flowVersionMapper.insert(version);
        return version;
    }

    public OperatorDefinition requireOperator(Long id) {
        OperatorDefinition definition = id == null ? null : operatorDefinitionMapper.selectById(id);
        if (definition == null || Integer.valueOf(1).equals(definition.getDeleted())) {
            throw new BusinessException(404, "算子不存在: " + id);
        }
        requireOwner(definition.getOwnerUserId(), "无权访问该算子");
        return definition;
    }

    private OperatorDefinition requireOperatorReadable(Long id) {
        OperatorDefinition definition = id == null ? null : operatorDefinitionMapper.selectById(id);
        if (definition == null || Integer.valueOf(1).equals(definition.getDeleted())) {
            throw new BusinessException(404, "算子不存在: " + id);
        }
        if (!"SYSTEM".equals(definition.getOwnerUserId())) {
            requireOwner(definition.getOwnerUserId(), "无权访问该算子");
        }
        return definition;
    }

    public FlowDefinition requireFlow(Long id) {
        FlowDefinition definition = id == null ? null : flowDefinitionMapper.selectById(id);
        if (definition == null || Integer.valueOf(1).equals(definition.getDeleted())) {
            throw new BusinessException(404, "流程不存在: " + id);
        }
        requireOwner(definition.getOwnerUserId(), "无权访问该流程");
        return definition;
    }

    public OperatorVersion requireOperatorVersionVisible(Long id) {
        OperatorVersion version = operatorVersionMapper.selectById(id);
        if (version == null) throw new BusinessException(404, "算子版本不存在: " + id);
        OperatorDefinition definition = operatorDefinitionMapper.selectById(version.getOperatorId());
        if (definition == null || Integer.valueOf(1).equals(definition.getDeleted())) {
            throw new BusinessException(404, "算子版本所属定义不存在: " + id);
        }
        if (!VersionStatus.PUBLISHED.equals(version.getStatus())) {
            requireOwner(definition.getOwnerUserId(), "无权引用未发布的算子版本: " + id);
        }
        return version;
    }

    public FlowVersion requireFlowVersion(Long id) {
        FlowVersion version = id == null ? null : flowVersionMapper.selectById(id);
        if (version == null) throw new BusinessException(404, "流程版本不存在: " + id);
        requireFlow(version.getFlowId());
        return version;
    }

    public OperatorDefinition requireOperatorDefinitionForVersion(Long versionId) {
        OperatorVersion version = requireOperatorVersionVisible(versionId);
        OperatorDefinition definition = operatorDefinitionMapper.selectById(version.getOperatorId());
        if (definition == null || Integer.valueOf(1).equals(definition.getDeleted())) {
            throw new BusinessException(404, "算子版本所属定义不存在: " + versionId);
        }
        return definition;
    }

    @SuppressWarnings("unchecked")
    private List<String> compositionSteps(Map<String, Object> composition) {
        List<String> result = new ArrayList<>();
        Object steps = composition.get("steps");
        if (steps instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("op") != null) result.add(String.valueOf(map.get("op")));
            }
        }
        return result.stream().distinct().toList();
    }

    private void validateRuleArtifact(Map<String, Object> artifact) {
        String language = text(artifact.get("language"));
        String entrypoint = text(artifact.get("entrypoint"));
        String source = text(artifact.get("sourceCode"));
        if (!"python".equalsIgnoreCase(language)) {
            throw new BusinessException(422, "当前自定义规则工件仅支持python，后续可扩展其他沙箱语言");
        }
        if (entrypoint == null || !entrypoint.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new BusinessException(422, "自定义规则entrypoint格式不正确");
        }
        if (source == null || source.length() > 50_000) {
            throw new BusinessException(422, "自定义规则sourceCode不能为空且不能超过50000字符");
        }
        if (!(artifact.get("tests") instanceof List<?> tests) || tests.isEmpty()) {
            throw new BusinessException(422, "自定义规则工件至少需要一个测试用例");
        }
        if (!Boolean.TRUE.equals(artifact.get("sandboxValidated"))) {
            throw new BusinessException(422, "自定义规则工件必须先通过隔离沙箱测试门禁");
        }
    }

    private void validateOutputSpec(Map<String, Object> spec) {
        if ("2".equals(String.valueOf(spec.get("specVersion"))) || spec.get("targets") instanceof List<?>) {
            List<Map<String, Object>> transformations = listOfMapsOptional(spec.get("transformations"));
            List<Map<String, Object>> targets = listOfMapsOptional(spec.get("targets"));
            if (targets.isEmpty()) throw new BusinessException(422, "输出算子至少需要一个输出目标");
            for (Map<String, Object> transformation : transformations) {
                OutputCapabilityRegistryService.CapabilitySnapshot capability =
                    outputCapabilityRegistryService.requireRunnableSnapshot(transformation);
                if (!"TRANSFORM".equals(capability.capabilityType())) {
                    throw new BusinessException(422, capability.code() + "不是转换能力");
                }
            }
            for (Map<String, Object> target : targets) {
                OutputCapabilityRegistryService.CapabilitySnapshot capability =
                    outputCapabilityRegistryService.requireRunnableSnapshot(target);
                if ("TRANSFORM".equals(capability.capabilityType())) {
                    throw new BusinessException(422, capability.code() + "不能作为输出目标");
                }
            }
            if (spec.get("draftId") != null
                    && (!Boolean.TRUE.equals(spec.get("sandboxShaped"))
                    || !Boolean.TRUE.equals(spec.get("previewValidated")))) {
                throw new BusinessException(422, "对话生成的输出工件必须通过整形和预览门禁");
            }
            return;
        }
        String kind = requiredText(spec, "outputKind").toUpperCase(Locale.ROOT);
        if (!Set.of("LEAD", "CHART", "TABLE", "EXCEL").contains(kind)) {
            throw new BusinessException(422, "outputKind仅支持LEAD/CHART/TABLE/EXCEL");
        }
        Object contentSpec = spec.getOrDefault("contentSpec", Map.of());
        object(contentSpec, "contentSpec");
        if (spec.get("draftId") != null
                && (!Boolean.TRUE.equals(spec.get("sandboxShaped"))
                || !Boolean.TRUE.equals(spec.get("previewValidated")))) {
            throw new BusinessException(422, "对话生成的输出工件必须通过整形和预览门禁");
        }
    }

    private void validateMiningRuntime(Map<String, Object> spec) {
        Long modelId = DagValidationService.toLong(spec.get("modelId"));
        if (modelId == null || modelId <= 0) {
            throw new BusinessException(422, "MINING_RUNTIME必须绑定有效modelId");
        }
        validatePublicField(spec.get("predictionField"), "predictionField");
        validatePublicField(spec.get("probabilityField"), "probabilityField");
        if (spec.get("probabilityIndex") != null) {
            Long index = DagValidationService.toLong(spec.get("probabilityIndex"));
            if (index == null || index < 0) throw new BusinessException(422, "probabilityIndex必须是非负整数");
        }
    }

    private void validatePublicField(Object raw, String name) {
        if (raw == null) return;
        String field = String.valueOf(raw).trim();
        if (!field.matches("^[A-Za-z_][A-Za-z0-9_]{0,99}$") || field.startsWith("__")) {
            throw new BusinessException(422, name + "不是合法字段名");
        }
    }

    private Map<String, Object> paletteMetadata(OperatorDefinition definition, OperatorVersion version) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> payload = object(version.getImplementationPayload(), "算子工件");
        if (OperatorTypes.OUTPUT.equals(definition.getOperatorType())) {
            List<Map<String, Object>> targets = listOfMapsOptional(payload.get("targets"));
            if (!targets.isEmpty()) {
                result.put("specVersion", 2);
                result.put("targetCount", targets.size());
                result.put("capabilityCodes", targets.stream()
                    .map(target -> target.get("capabilityCode")).toList());
                Map<String, Object> firstConfig = object(targets.get(0).getOrDefault("config", Map.of()), "config");
                result.put("outputKind", targetKind(targets.get(0)));
                if (firstConfig.get("title") != null) result.put("title", firstConfig.get("title"));
            } else {
                result.put("outputKind", payload.get("outputKind"));
                Map<String, Object> contentSpec = object(payload.getOrDefault("contentSpec", Map.of()), "contentSpec");
                if (contentSpec.get("title") != null) result.put("title", contentSpec.get("title"));
                if (contentSpec.get("sheetName") != null) result.put("sheetName", contentSpec.get("sheetName"));
            }
        } else if (OperatorTypes.DATA.equals(definition.getOperatorType())
                && "SQL_AST".equals(version.getImplementationType())) {
            result.put("dataSourceId", payload.get("dataSourceId"));
            result.put("allowedTables", payload.getOrDefault("allowedTables", List.of()));
            result.put("maxRows", payload.getOrDefault("maxRows", 1000));
        } else if (OperatorTypes.AGENT.equals(definition.getOperatorType())) {
            result.put("model", payload.get("model"));
            result.put("allowedTools", payload.getOrDefault("allowedTools", List.of()));
            result.put("maxTurns", payload.getOrDefault("maxTurns", 3));
            result.put("maxToolCalls", payload.getOrDefault("maxToolCalls", 4));
        }
        return Map.copyOf(result);
    }

    static List<String> outputNodeErrors(String nodeId, String versionStatus,
                                         boolean authoredVersion, Map<String, Object> config) {
        List<String> errors = new ArrayList<>();
        if (!VersionStatus.PUBLISHED.equals(versionStatus)) {
            errors.add("输出节点[" + nodeId + "]只能绑定已发布算子版本");
        }
        if (authoredVersion && (config.containsKey("contentSpec") || config.containsKey("leadPolicy")
                || config.containsKey("targets") || config.containsKey("transformations"))) {
            errors.add("输出节点[" + nodeId + "]不能覆盖已发布版本的转换、输出目标或策略");
        }
        return List.copyOf(errors);
    }

    private List<Map<String, Object>> listOfMapsOptional(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) throw new BusinessException(422, "输出能力条目必须是对象");
            result.add(object(item, "输出能力条目"));
        }
        return List.copyOf(result);
    }

    private String targetKind(Map<String, Object> target) {
        Map<String, Object> config = object(target.getOrDefault("config", Map.of()), "target.config");
        if (config.get("legacyOutputKind") != null) return String.valueOf(config.get("legacyOutputKind"));
        return switch (String.valueOf(target.get("implementationType"))) {
            case "LEAD" -> "LEAD";
            case "ECHARTS" -> "CHART";
            case "TABLE" -> "TABLE";
            case "COMPOSED_PAGE" -> "DASHBOARD";
            default -> String.valueOf(target.get("capabilityType"));
        };
    }

    static List<String> nodeTimeoutErrors(String nodeId, Map<String, Object> config) {
        if (config == null || !config.containsKey("nodeTimeoutSeconds")) return List.of();
        Long timeout = DagValidationService.toLong(config.get("nodeTimeoutSeconds"));
        if (timeout == null || timeout < 1 || timeout > 3600) {
            return List.of("节点[" + nodeId + "]的nodeTimeoutSeconds必须是1到3600之间的整数");
        }
        return List.of();
    }

    static List<String> outputTerminalErrors(Set<String> outputNodeIds,
                                             List<Map<String, Object>> edges) {
        return edges.stream()
            .map(edge -> String.valueOf(edge.get("source")))
            .filter(outputNodeIds::contains)
            .distinct()
            .map(source -> "输出节点[" + source + "]必须是终点，不能连接下游节点")
            .toList();
    }

    static List<String> sourceRootErrors(Set<String> sourceOnlyNodeIds,
                                         List<Map<String, Object>> edges) {
        return edges.stream()
            .map(edge -> String.valueOf(edge.get("target")))
            .filter(sourceOnlyNodeIds::contains)
            .distinct()
            .map(target -> "SQL_AST数据节点[" + target + "]必须是入口，不能接收上游连线")
            .toList();
    }

    private List<Map<String, Object>> listOfMaps(Object value, String field) {
        try {
            List<Map<String, Object>> converted = objectMapper.convertValue(
                value, new TypeReference<List<Map<String, Object>>>() {});
            if (converted == null) throw new IllegalArgumentException("null");
            return converted;
        } catch (Exception e) {
            throw new BusinessException(field + "必须是对象数组");
        }
    }

    private Map<String, Object> object(Object value, String field) {
        try {
            if (value instanceof String text) {
                return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
            }
            return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(field + "必须是JSON对象");
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException("版本快照序列化失败: " + e.getMessage()); }
    }

    private String requiredText(Map<String, Object> body, String field) {
        String value = text(body == null ? null : body.get(field));
        if (value == null) throw new BusinessException(field + "不能为空");
        return value;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String currentUserId() {
        return UserContextHolder.require().userId().toString();
    }

    private boolean isAdmin() {
        return roleService.currentUserHas(PermissionCodes.RESOURCE_ACCESS_ALL);
    }

    private void requireOwner(String owner, String message) {
        if (!isAdmin() && (owner == null || !owner.equals(currentUserId()))) {
            throw new BusinessException(403, message);
        }
    }

    public record PublishedOperatorView(Long operatorId, Long operatorVersionId,
                                        String code, String name, String description,
                                        String operatorType, Integer versionNo,
                                        String implementationType, String inputSchema,
                                        String outputSchema, String parameterSchema,
                                        Long runtimeProfileId, String runtimeProfileCode,
                                        String imageDigest, Map<String, Object> metadata) {}

    public record FlowValidationReport(boolean valid, List<String> errors, List<String> warnings,
                                       List<String> topologicalOrder,
                                       List<List<String>> executionLevels,
                                       List<SchemaCompatibilityService.EdgeContractReport> edgeContracts) {}
}
