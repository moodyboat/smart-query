package com.smartquery.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.Result;
import com.smartquery.entity.FlowDefinition;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.Lead;
import com.smartquery.entity.NodeRun;
import com.smartquery.entity.NodeReplay;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.entity.OutputDraft;
import com.smartquery.entity.PolicyDraft;
import com.smartquery.entity.RulePrimitive;
import com.smartquery.entity.RuleDraft;
import com.smartquery.entity.DependencyRequest;
import com.smartquery.entity.DraftDependency;
import com.smartquery.entity.RuntimeProfile;
import com.smartquery.entity.RuntimeBuildJob;
import com.smartquery.entity.ArchiveRecord;
import com.smartquery.entity.StoragePolicy;
import com.smartquery.orchestration.DagValidationService;
import com.smartquery.orchestration.DependencyCenterService;
import com.smartquery.orchestration.LeadService;
import com.smartquery.orchestration.OrchestrationRunService;
import com.smartquery.orchestration.NodeReplayService;
import com.smartquery.orchestration.OutputArtifactService;
import com.smartquery.orchestration.OutputArtifactQueryService;
import com.smartquery.orchestration.OutputAuthoringService;
import com.smartquery.orchestration.OperatorApprovalService;
import com.smartquery.orchestration.PolicyAuthoringService;
import com.smartquery.orchestration.RuleCompositionService;
import com.smartquery.orchestration.RuleAuthoringService;
import com.smartquery.orchestration.RuntimeProfileService;
import com.smartquery.orchestration.RuntimeBuildJobService;
import com.smartquery.orchestration.RuntimeBuildWorkerAuthService;
import com.smartquery.orchestration.RuntimeBuildWorkerService;
import com.smartquery.orchestration.AgentPolicyService;
import com.smartquery.orchestration.VersionCatalogService;
import com.smartquery.orchestration.StorageGovernanceService;
import com.smartquery.orchestration.StorageRetentionScheduler;
import com.smartquery.orchestration.execution.OperatorExecutorRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Additive V2 control-plane API. Existing V1 mining APIs remain unchanged. */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class OrchestrationV2Controller {

    private final VersionCatalogService versionCatalogService;
    private final RuleCompositionService ruleCompositionService;
    private final RuleAuthoringService ruleAuthoringService;
    private final LeadService leadService;
    private final OrchestrationRunService orchestrationRunService;
    private final NodeReplayService nodeReplayService;
    private final OutputArtifactService outputArtifactService;
    private final OutputArtifactQueryService outputArtifactQueryService;
    private final OutputAuthoringService outputAuthoringService;
    private final OperatorApprovalService operatorApprovalService;
    private final PolicyAuthoringService policyAuthoringService;
    private final AgentPolicyService agentPolicyService;
    private final DependencyCenterService dependencyCenterService;
    private final RuntimeProfileService runtimeProfileService;
    private final RuntimeBuildJobService runtimeBuildJobService;
    private final RuntimeBuildWorkerAuthService runtimeBuildWorkerAuthService;
    private final RuntimeBuildWorkerService runtimeBuildWorkerService;
    private final StorageGovernanceService storageGovernanceService;
    private final StorageRetentionScheduler storageRetentionScheduler;
    private final OperatorExecutorRegistry executorRegistry;
    private final ObjectMapper objectMapper;

    @GetMapping("/rule-capabilities")
    public Result<List<RulePrimitive>> ruleCapabilities() {
        return Result.ok(ruleCompositionService.listCapabilities());
    }

    @PostMapping("/rule-compositions/validate")
    public Result<RuleCompositionService.RuleValidationReport> validateRule(
            @RequestBody Map<String, Object> composition) {
        return Result.ok(ruleCompositionService.validate(composition));
    }

    @PostMapping("/dags/validate")
    public Result<VersionCatalogService.FlowValidationReport> validateDag(
            @RequestBody Map<String, Object> body) {
        try {
            List<Map<String, Object>> nodes = objectMapper.convertValue(
                body.getOrDefault("nodes", List.of()), new TypeReference<>() {});
            List<Map<String, Object>> edges = objectMapper.convertValue(
                body.getOrDefault("edges", List.of()), new TypeReference<>() {});
            return Result.ok(versionCatalogService.validateFlowDraft(nodes, edges));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("nodes和edges必须是对象数组");
        }
    }

    @GetMapping("/operators")
    public Result<List<OperatorDefinition>> operators() {
        return Result.ok(versionCatalogService.listOperators());
    }

    @GetMapping("/operator-catalog")
    public Result<List<VersionCatalogService.PublishedOperatorView>> publishedOperatorCatalog(
            @RequestParam(required = false) String operatorType) {
        return Result.ok(versionCatalogService.listPublishedOperatorCatalog(operatorType));
    }

    @PostMapping("/operators")
    public Result<OperatorDefinition> createOperator(@RequestBody Map<String, Object> body) {
        return Result.ok(versionCatalogService.createOperator(body));
    }

    @GetMapping("/operators/{operatorId}/versions")
    public Result<List<OperatorVersion>> operatorVersions(@PathVariable Long operatorId) {
        return Result.ok(versionCatalogService.listOperatorVersions(operatorId));
    }

    @PostMapping("/operators/{operatorId}/versions")
    public Result<OperatorVersion> createOperatorVersion(@PathVariable Long operatorId,
                                                          @RequestBody Map<String, Object> body) {
        if ("SANDBOX_EXTENSION".equalsIgnoreCase(String.valueOf(body.get("implementationType")))) {
            throw new BusinessException(422, "自定义规则版本必须通过rule-drafts沙箱验证流程创建");
        }
        if ("OUTPUT_RENDERER".equalsIgnoreCase(String.valueOf(body.get("implementationType")))) {
            throw new BusinessException(422, "输出算子版本必须通过output-drafts整形和预览流程发布");
        }
        if (List.of("SQL_AST", "AGENT_POLICY").contains(
                String.valueOf(body.get("implementationType")).trim().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(422, "SQL/智能体版本必须通过policy-drafts整形和预览流程发布");
        }
        return Result.ok(versionCatalogService.createOperatorVersion(operatorId, body));
    }

    @GetMapping("/operators/{operatorId}/rule-drafts")
    public Result<List<RuleDraft>> ruleDrafts(@PathVariable Long operatorId) {
        return Result.ok(ruleAuthoringService.list(operatorId));
    }

    @PostMapping("/operators/{operatorId}/rule-drafts/from-dialogue")
    public Result<RuleDraft> generateRuleDraft(@PathVariable Long operatorId,
                                               @RequestBody Map<String, Object> body) {
        return Result.ok(ruleAuthoringService.generate(operatorId, body));
    }

    @PostMapping("/operators/{operatorId}/rule-drafts/{draftId}/candidate-version")
    public Result<OperatorVersion> createRuleCandidate(@PathVariable Long operatorId,
                                                        @PathVariable Long draftId) {
        return Result.ok(ruleAuthoringService.createCandidateVersion(operatorId, draftId));
    }

    @PostMapping("/operators/{operatorId}/rule-drafts/{draftId}/validate")
    public Result<RuleDraft> validateRuleDraft(@PathVariable Long operatorId,
                                               @PathVariable Long draftId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        Long profileId = body == null ? null : DagValidationService.toLong(body.get("runtimeProfileId"));
        return Result.ok(ruleAuthoringService.validateDraft(operatorId, draftId, profileId));
    }

    @GetMapping("/operators/{operatorId}/output-drafts")
    public Result<List<OutputDraft>> outputDrafts(@PathVariable Long operatorId) {
        return Result.ok(outputAuthoringService.list(operatorId));
    }

    @PostMapping("/operators/{operatorId}/output-drafts/from-dialogue")
    public Result<OutputDraft> generateOutputDraft(@PathVariable Long operatorId,
                                                   @RequestBody Map<String, Object> body) {
        return Result.ok(outputAuthoringService.generate(operatorId, body));
    }

    @PostMapping("/operators/{operatorId}/output-drafts/{draftId}/shape")
    public Result<OutputDraft> shapeOutputDraft(@PathVariable Long operatorId,
                                                @PathVariable Long draftId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Long profileId = body == null ? null : DagValidationService.toLong(body.get("runtimeProfileId"));
        return Result.ok(outputAuthoringService.shape(operatorId, draftId, profileId));
    }

    @PostMapping("/operators/{operatorId}/output-drafts/{draftId}/preview")
    public Result<OutputAuthoringService.PreviewResult> previewOutputDraft(
            @PathVariable Long operatorId, @PathVariable Long draftId,
            @RequestBody Map<String, Object> body) {
        return Result.ok(outputAuthoringService.preview(operatorId, draftId, body));
    }

    @PostMapping("/operators/{operatorId}/output-drafts/{draftId}/publish-version")
    public Result<OperatorVersion> publishOutputDraft(@PathVariable Long operatorId,
                                                       @PathVariable Long draftId) {
        return Result.ok(outputAuthoringService.publish(operatorId, draftId));
    }

    @GetMapping("/agent-tools")
    public Result<List<AgentPolicyService.AgentToolView>> agentTools() {
        return Result.ok(agentPolicyService.eligibleTools());
    }

    @GetMapping("/operators/{operatorId}/policy-drafts")
    public Result<List<PolicyDraft>> policyDrafts(@PathVariable Long operatorId) {
        return Result.ok(policyAuthoringService.list(operatorId));
    }

    @PostMapping("/operators/{operatorId}/policy-drafts/from-dialogue")
    public Result<PolicyDraft> generatePolicyDraft(@PathVariable Long operatorId,
                                                   @RequestBody Map<String, Object> body) {
        return Result.ok(policyAuthoringService.generate(operatorId, body));
    }

    @PostMapping("/operators/{operatorId}/policy-drafts/{draftId}/shape")
    public Result<PolicyDraft> shapePolicyDraft(@PathVariable Long operatorId,
                                                @PathVariable Long draftId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Long profileId = body == null ? null : DagValidationService.toLong(body.get("runtimeProfileId"));
        return Result.ok(policyAuthoringService.shape(operatorId, draftId, profileId));
    }

    @PostMapping("/operators/{operatorId}/policy-drafts/{draftId}/preview")
    public Result<PolicyAuthoringService.PreviewResult> previewPolicyDraft(
            @PathVariable Long operatorId, @PathVariable Long draftId,
            @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(policyAuthoringService.preview(operatorId, draftId, body == null ? Map.of() : body));
    }

    @PostMapping("/operators/{operatorId}/policy-drafts/{draftId}/publish-version")
    public Result<OperatorVersion> publishPolicyDraft(@PathVariable Long operatorId,
                                                       @PathVariable Long draftId) {
        return Result.ok(policyAuthoringService.publish(operatorId, draftId));
    }

    @GetMapping("/operator-version-approvals")
    public Result<List<OperatorApprovalService.ApprovalView>> operatorVersionApprovals(
            @RequestParam(required = false) String status) {
        return Result.ok(operatorApprovalService.list(status));
    }

    @GetMapping("/operator-version-approvals/capability")
    public Result<Map<String, Object>> operatorApprovalCapability() {
        return Result.ok(Map.of("canReview", operatorApprovalService.currentUserCanReview()));
    }

    @GetMapping("/operator-version-approvals/{approvalId}")
    public Result<OperatorApprovalService.ApprovalDetail> operatorVersionApprovalDetail(
            @PathVariable Long approvalId) {
        return Result.ok(operatorApprovalService.detail(approvalId));
    }

    @PostMapping("/operators/{operatorId}/versions/{versionId}/submit-approval")
    public Result<com.smartquery.entity.OperatorVersionApproval> submitOperatorVersionApproval(
            @PathVariable Long operatorId, @PathVariable Long versionId,
            @RequestBody(required = false) Map<String, Object> body) {
        String comment = body == null ? null : String.valueOf(body.getOrDefault("comment", ""));
        return Result.ok(operatorApprovalService.submit(operatorId, versionId, comment));
    }

    @PostMapping("/operator-version-approvals/{approvalId}/review")
    public Result<OperatorApprovalService.ApprovalView> reviewOperatorVersionApproval(
            @PathVariable Long approvalId, @RequestBody Map<String, Object> body) {
        return Result.ok(operatorApprovalService.review(approvalId, body));
    }

    @GetMapping("/flows")
    public Result<List<FlowDefinition>> flows() {
        return Result.ok(versionCatalogService.listFlows());
    }

    @PostMapping("/flows")
    public Result<FlowDefinition> createFlow(@RequestBody Map<String, Object> body) {
        return Result.ok(versionCatalogService.createFlow(body));
    }

    @GetMapping("/flows/{flowId}/versions")
    public Result<List<FlowVersion>> flowVersions(@PathVariable Long flowId) {
        return Result.ok(versionCatalogService.listFlowVersions(flowId));
    }

    @PostMapping("/flows/{flowId}/versions")
    public Result<FlowVersion> createFlowVersion(@PathVariable Long flowId,
                                                 @RequestBody Map<String, Object> body) {
        return Result.ok(versionCatalogService.createFlowVersion(flowId, body));
    }

    @GetMapping("/runtime-capabilities")
    public Result<List<String>> runtimeCapabilities() {
        return Result.ok(executorRegistry.enabledImplementationTypes());
    }

    @GetMapping("/dependency-requests")
    public Result<List<DependencyRequest>> dependencyRequests(
            @RequestParam(required = false) String status) {
        return Result.ok(dependencyCenterService.listRequests(status));
    }

    @PostMapping("/dependency-requests")
    public Result<DependencyRequest> submitDependencyRequest(@RequestBody Map<String, Object> body) {
        return Result.ok(dependencyCenterService.submit(body));
    }

    @PostMapping("/dependency-requests/{requestId}/review")
    public Result<DependencyRequest> reviewDependencyRequest(@PathVariable Long requestId,
                                                              @RequestBody Map<String, Object> body) {
        return Result.ok(dependencyCenterService.review(requestId, body));
    }

    @PostMapping("/dependency-requests/{requestId}/deprecate")
    public Result<DependencyRequest> deprecateDependencyRequest(@PathVariable Long requestId) {
        return Result.ok(dependencyCenterService.deprecateRequest(requestId));
    }

    @GetMapping("/runtime-profiles")
    public Result<List<DependencyCenterService.ProfileView>> runtimeProfiles(
            @RequestParam(required = false) String runtimeType,
            @RequestParam(defaultValue = "false") boolean includeDeprecated) {
        return Result.ok(dependencyCenterService.listProfiles(runtimeType, includeDeprecated));
    }

    @PostMapping("/runtime-profiles/register-build")
    public Result<RuntimeProfile> registerRuntimeBuild(@RequestBody Map<String, Object> body) {
        return Result.ok(runtimeBuildWorkerService.registerManual(body));
    }

    @GetMapping("/runtime-build-jobs")
    public Result<List<RuntimeBuildJob>> runtimeBuildJobs(
            @RequestParam(required = false) String status) {
        return Result.ok(runtimeBuildJobService.list(status));
    }

    @GetMapping("/runtime-build-jobs/capability")
    public Result<Map<String, Object>> runtimeBuildCapability() {
        return Result.ok(Map.of("workerEnabled", runtimeBuildWorkerAuthService.enabled(),
            "protocol", "HMAC_PULL_V1"));
    }

    @PostMapping("/runtime-build-jobs/{jobId}/retry")
    public Result<RuntimeBuildJob> retryRuntimeBuild(@PathVariable Long jobId) {
        return Result.ok(runtimeBuildJobService.retry(jobId));
    }

    @PostMapping("/runtime-build-jobs/{jobId}/cancel")
    public Result<RuntimeBuildJob> cancelRuntimeBuild(@PathVariable Long jobId) {
        return Result.ok(runtimeBuildJobService.cancel(jobId));
    }

    @PostMapping("/runtime-profiles/{profileId}/deprecate")
    public Result<RuntimeProfile> deprecateRuntimeProfile(@PathVariable Long profileId) {
        return Result.ok(dependencyCenterService.deprecateProfile(profileId));
    }

    @GetMapping("/operator-versions/{versionId}/runtime")
    public Result<RuntimeProfileService.RuntimeBindingView> operatorVersionRuntime(
            @PathVariable Long versionId) {
        versionCatalogService.requireOperatorVersionVisible(versionId);
        return Result.ok(runtimeProfileService.binding(versionId));
    }

    @GetMapping("/drafts/{draftType}/{draftId}/dependencies")
    public Result<List<DraftDependency>> draftDependencies(@PathVariable String draftType,
                                                            @PathVariable Long draftId) {
        return Result.ok(dependencyCenterService.draftDependencies(draftType, draftId));
    }

    @PostMapping("/flow-versions/{flowVersionId}/trial-runs")
    public Result<OrchestrationRun> submitTrial(@PathVariable Long flowVersionId,
                                                @RequestBody Map<String, Object> input) {
        return Result.ok(orchestrationRunService.submitTrial(flowVersionId, input));
    }

    @GetMapping("/runs/{runId}")
    public Result<OrchestrationRun> run(@PathVariable Long runId) {
        return Result.ok(orchestrationRunService.getRun(runId));
    }

    @GetMapping("/flows/{flowId}/runs")
    public Result<List<OrchestrationRun>> flowRuns(@PathVariable Long flowId,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(orchestrationRunService.listFlowRuns(flowId, limit));
    }

    @PostMapping("/runs/{runId}/cancel")
    public Result<OrchestrationRun> cancelRun(@PathVariable Long runId) {
        return Result.ok(orchestrationRunService.cancel(runId));
    }

    @GetMapping("/runs/{runId}/nodes")
    public Result<List<NodeRun>> nodeRuns(@PathVariable Long runId) {
        return Result.ok(orchestrationRunService.listNodeRuns(runId));
    }

    @PostMapping("/runs/{runId}/nodes/{nodeRunId}/replays")
    public Result<NodeReplay> createNodeReplay(@PathVariable Long runId,
                                               @PathVariable Long nodeRunId) {
        return Result.ok(nodeReplayService.create(runId, nodeRunId));
    }

    @GetMapping("/runs/{runId}/node-replays")
    public Result<List<NodeReplay>> nodeReplays(@PathVariable Long runId) {
        return Result.ok(nodeReplayService.list(runId));
    }

    @GetMapping("/node-replays/{replayId}")
    public Result<NodeReplayService.ReplayDetail> nodeReplay(@PathVariable Long replayId) {
        return Result.ok(nodeReplayService.detail(replayId));
    }

    @PostMapping("/node-replays/{replayId}/cancel")
    public Result<NodeReplay> cancelNodeReplay(@PathVariable Long replayId) {
        return Result.ok(nodeReplayService.cancel(replayId));
    }

    @GetMapping("/runs/{runId}/outputs")
    public Result<List<OutputArtifact>> outputs(@PathVariable Long runId) {
        return Result.ok(outputArtifactService.list(runId));
    }

    @GetMapping("/runs/{runId}/leads")
    public Result<List<Lead>> runLeads(@PathVariable Long runId) {
        orchestrationRunService.getRun(runId);
        return Result.ok(leadService.listByRun(runId));
    }

    @GetMapping("/outputs")
    public Result<List<OutputArtifact>> recentOutputs(
            @RequestParam(required = false) String outputKind,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(outputArtifactService.listRecent(outputKind, limit));
    }

    @GetMapping("/outputs/{artifactId}/view")
    public Result<OutputArtifactService.OutputView> outputView(
            @PathVariable Long artifactId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return Result.ok(outputArtifactService.view(artifactId, page, pageSize));
    }

    @PostMapping("/outputs/{artifactId}/query")
    public Result<OutputArtifactQueryService.QueryResult> queryOutput(
            @PathVariable Long artifactId,
            @RequestBody(required = false) OutputArtifactQueryService.QueryRequest request) {
        return Result.ok(outputArtifactQueryService.query(artifactId, request));
    }

    @GetMapping("/storage-governance/dashboard")
    public Result<StorageGovernanceService.GovernanceDashboard> storageGovernanceDashboard() {
        return Result.ok(storageGovernanceService.dashboard());
    }

    @PostMapping("/storage-governance/policy")
    public Result<StoragePolicy> updateStoragePolicy(@RequestBody Map<String, Object> body) {
        return Result.ok(storageGovernanceService.updatePolicy(body));
    }

    @PostMapping("/storage-governance/outputs/{artifactId}/archive")
    public Result<ArchiveRecord> archiveOutput(@PathVariable Long artifactId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(storageGovernanceService.archiveOutput(artifactId, reason(body)));
    }

    @PostMapping("/storage-governance/node-replays/{replayId}/archive")
    public Result<ArchiveRecord> archiveReplay(@PathVariable Long replayId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(storageGovernanceService.archiveReplay(replayId, reason(body)));
    }

    @PostMapping("/storage-governance/archives/{archiveId}/restore")
    public Result<ArchiveRecord> restoreArchive(@PathVariable Long archiveId) {
        return Result.ok(storageGovernanceService.restore(archiveId));
    }

    @PostMapping("/storage-governance/retention/run")
    public Result<StorageGovernanceService.GovernanceDashboard> runRetentionNow() {
        storageGovernanceService.dashboard();
        storageRetentionScheduler.runNow();
        return Result.ok(storageGovernanceService.dashboard());
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runEvents(@PathVariable Long runId,
                                @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        return orchestrationRunService.subscribe(runId, lastEventId);
    }

    @GetMapping("/leads")
    public Result<List<Lead>> leads(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String leadType,
                                    @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(leadService.list(status, leadType, limit));
    }

    @GetMapping("/leads/{leadId}")
    public Result<LeadService.LeadDetail> lead(@PathVariable Long leadId) {
        return Result.ok(leadService.getDetail(leadId));
    }

    private String reason(Map<String, Object> body) {
        return body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
    }
}
