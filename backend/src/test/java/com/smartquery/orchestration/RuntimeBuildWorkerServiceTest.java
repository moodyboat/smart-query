package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.RuntimeBuildJob;
import com.smartquery.entity.RuntimeProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeBuildWorkerServiceTest {
    private final RuntimeBuildJobService jobs = mock(RuntimeBuildJobService.class);
    private final DependencyCenterService dependencies = mock(DependencyCenterService.class);
    private final DraftRevalidationService revalidation = mock(DraftRevalidationService.class);
    private final RuntimeBuildWorkerService service = new RuntimeBuildWorkerService(
        jobs, dependencies, revalidation, new ObjectMapper());

    @Test
    void successfulCallbackCannotReplaceApprovedDependencyRequest() {
        RuntimeBuildJob job = new RuntimeBuildJob();
        job.setId(3L);
        job.setJobNo("RB3");
        job.setDependencyRequestId(7L);
        job.setBaseProfileId(2L);
        job.setApprovedByUserId("10");
        job.setWorkerId("ci-1");
        job.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(10));
        job.setBuildSpec("{\"profileCode\":\"rule-python-rb3\",\"profileName\":\"Rule Python pandas\",\"requestIds\":[7]}");
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(99L);
        when(jobs.requireLeased("RB3", "lease")).thenReturn(job);
        when(dependencies.registerBuiltRuntimeFromBuilder(any(), eq("BUILD:RB3"), eq("10")))
            .thenReturn(profile);
        when(revalidation.revalidate(List.of(7L), 99L)).thenReturn(Map.of("successful", 1));

        service.complete("RB3", "lease", """
            {"status":"SUCCEEDED","requestIds":[999],
             "imageRef":"registry.example/rule@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "imageDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "builder":"enterprise-ci/42",
             "sbom":{"uri":"https://artifacts.example/sbom/42","digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
             "provenance":{"uri":"oci://registry.example/provenance/42","digest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},
             "security":{"sourceVerified":true,"licenseDecision":"APPROVED","critical":0,"high":0}}
            """);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> registration = ArgumentCaptor.forClass(Map.class);
        verify(dependencies).registerBuiltRuntimeFromBuilder(registration.capture(), eq("BUILD:RB3"), eq("10"));
        assertEquals(List.of(7L), registration.getValue().get("requestIds"));
        assertEquals(2L, registration.getValue().get("baseProfileId"));
        verify(jobs).recordSuccess(eq(job), eq(99L), any(), any());
    }
}
