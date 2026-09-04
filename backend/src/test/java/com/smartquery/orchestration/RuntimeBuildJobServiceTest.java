package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smartquery.entity.DependencyRequest;
import com.smartquery.entity.DraftDependency;
import com.smartquery.entity.RuntimeBuildJob;
import com.smartquery.mapper.RuntimeBuildJobMapper;
import com.smartquery.mapper.RuntimeProfileMapper;
import com.smartquery.mapper.DependencyRequestMapper;
import com.smartquery.mapper.DraftDependencyMapper;
import com.smartquery.service.ResourceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeBuildJobServiceTest {
    private final RuntimeBuildJobMapper jobs = mock(RuntimeBuildJobMapper.class);
    private final RuntimeProfileMapper profiles = mock(RuntimeProfileMapper.class);
    private final DependencyRequestMapper requests = mock(DependencyRequestMapper.class);
    private final DraftDependencyMapper draftDependencies = mock(DraftDependencyMapper.class);
    private final ResourceAccessService access = mock(ResourceAccessService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeBuildJobService service = new RuntimeBuildJobService(jobs, profiles,
        requests, draftDependencies, access, objectMapper);

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
            RuntimeBuildJob.class);
    }

    @Test
    void approvedDependencyBecomesImmutableQueuedBuildSpec() throws Exception {
        when(jobs.selectOne(any())).thenReturn(null);
        when(profiles.selectOne(any())).thenReturn(null);
        DependencyRequest request = request();

        RuntimeBuildJob result = service.enqueue(request);

        ArgumentCaptor<RuntimeBuildJob> inserted = ArgumentCaptor.forClass(RuntimeBuildJob.class);
        verify(jobs).insert(inserted.capture());
        assertEquals(RuntimeBuildStatus.QUEUED, result.getStatus());
        Map<String, Object> spec = objectMapper.readValue(inserted.getValue().getBuildSpec(), new TypeReference<>() {});
        assertEquals("runtime-build/v1", spec.get("schemaVersion"));
        assertEquals("RULE_PYTHON", spec.get("runtimeType"));
        assertEquals(false, ((Map<?, ?>) spec.get("policy")).get("mutableTags"));
        assertEquals("pandas", ((Map<?, ?>) ((List<?>) spec.get("dependencies")).get(0)).get("name"));
    }

    @Test
    void claimReturnsOneTimeLeaseAndFixedCallbackPath() {
        RuntimeBuildJob candidate = new RuntimeBuildJob();
        candidate.setId(3L);
        candidate.setJobNo("RB-3");
        candidate.setStatus(RuntimeBuildStatus.QUEUED);
        candidate.setAttemptNo(0);
        candidate.setBuildSpec("{\"schemaVersion\":\"runtime-build/v1\"}");
        RuntimeBuildJob claimed = new RuntimeBuildJob();
        claimed.setId(3L);
        claimed.setJobNo("RB-3");
        claimed.setStatus(RuntimeBuildStatus.BUILDING);
        claimed.setBuildSpec(candidate.getBuildSpec());
        when(jobs.selectList(any())).thenReturn(List.of(candidate));
        when(jobs.update(isNull(), any())).thenReturn(1);
        when(jobs.selectById(3L)).thenReturn(claimed);

        RuntimeBuildJobService.BuildClaim claim = service.claim("enterprise-ci-01", List.of("RULE_PYTHON"));

        assertNotNull(claim);
        assertFalse(claim.leaseToken().isBlank());
        assertEquals("/api/v2/runtime-build-worker/jobs/RB-3/complete", claim.callbackPath());
    }

    @Test
    void laterApprovalBuildsCompleteDependencySetForSameDraft() throws Exception {
        DependencyRequest trigger = request();
        DependencyRequest second = request();
        second.setId(8L);
        second.setDependencyName("scikit-learn");
        second.setResolvedVersion("1.6.1");
        second.setStatus("APPROVED");
        DraftDependency triggerLink = link(1L, 7L, "pandas");
        DraftDependency secondLink = link(2L, 8L, "scikit-learn");
        when(jobs.selectOne(any())).thenReturn(null);
        when(profiles.selectOne(any())).thenReturn(null);
        when(draftDependencies.selectList(any())).thenReturn(List.of(triggerLink),
            List.of(triggerLink, secondLink));
        when(requests.selectById(7L)).thenReturn(trigger);
        when(requests.selectById(8L)).thenReturn(second);

        RuntimeBuildJob result = service.enqueue(trigger);

        Map<String, Object> spec = objectMapper.readValue(result.getBuildSpec(), new TypeReference<>() {});
        assertEquals(List.of(7, 8), spec.get("requestIds"));
        assertEquals(2, ((List<?>) spec.get("dependencies")).size());
    }

    @ParameterizedTest(name = "{0} builds in {1}")
    @MethodSource("operatorRuntimeFamilies")
    void buildsImmutableSpecsForEveryOperatorRuntime(String dependencyType, String runtimeType)
            throws Exception {
        RuntimeBuildJobMapper localJobs = mock(RuntimeBuildJobMapper.class);
        RuntimeProfileMapper localProfiles = mock(RuntimeProfileMapper.class);
        DependencyRequestMapper localRequests = mock(DependencyRequestMapper.class);
        DraftDependencyMapper localLinks = mock(DraftDependencyMapper.class);
        RuntimeBuildJobService localService = new RuntimeBuildJobService(localJobs, localProfiles,
            localRequests, localLinks, access, objectMapper);
        when(localJobs.selectOne(any())).thenReturn(null);
        when(localProfiles.selectOne(any())).thenReturn(null);
        DependencyRequest request = request();
        request.setDependencyType(dependencyType);
        request.setRuntimeType(runtimeType);
        request.setDependencyName("test-" + dependencyType.toLowerCase());

        RuntimeBuildJob result = localService.enqueue(request);

        Map<String, Object> spec = objectMapper.readValue(result.getBuildSpec(), new TypeReference<>() {});
        assertEquals(runtimeType, spec.get("runtimeType"));
        assertEquals(false, ((Map<?, ?>) spec.get("policy")).get("runtimeInstall"));
        assertEquals(true, ((Map<?, ?>) spec.get("policy")).get("requireSbom"));
        assertEquals(dependencyType,
            ((Map<?, ?>) ((List<?>) spec.get("dependencies")).get(0)).get("type"));
    }

    static Stream<Arguments> operatorRuntimeFamilies() {
        return Stream.of(
            Arguments.of("JDBC_DRIVER", "DATA_CONNECTOR"),
            Arguments.of("PYTHON_PACKAGE", "RULE_PYTHON"),
            Arguments.of("ML_ALGORITHM", "ML_MODEL"),
            Arguments.of("AGENT_TOOL", "AGENT_GATEWAY"),
            Arguments.of("FRONTEND_RENDERER", "OUTPUT_RENDERER")
        );
    }

    private DependencyRequest request() {
        DependencyRequest request = new DependencyRequest();
        request.setId(7L);
        request.setDependencyType("PYTHON_PACKAGE");
        request.setRuntimeType("RULE_PYTHON");
        request.setDependencyName("pandas");
        request.setResolvedVersion("2.2.3");
        request.setSourceUri("https://packages.example/pandas");
        request.setChecksumSha256("a".repeat(64));
        request.setLicenseName("BSD-3-Clause");
        request.setOwnerUserId("9");
        request.setReviewedByUserId("10");
        request.setStatus("APPROVED");
        return request;
    }

    private DraftDependency link(Long id, Long requestId, String name) {
        DraftDependency link = new DraftDependency();
        link.setId(id);
        link.setRequestId(requestId);
        link.setDraftType("RULE");
        link.setDraftId(20L);
        link.setDependencyType("PYTHON_PACKAGE");
        link.setDependencyName(name);
        return link;
    }
}
