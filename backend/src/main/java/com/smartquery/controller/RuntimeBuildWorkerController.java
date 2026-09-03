package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.entity.RuntimeBuildJob;
import com.smartquery.orchestration.RuntimeBuildJobService;
import com.smartquery.orchestration.RuntimeBuildWorkerAuthService;
import com.smartquery.orchestration.RuntimeBuildWorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Pull-based API used by enterprise CI; authenticated independently from user JWT. */
@RestController
@RequestMapping("/api/v2/runtime-build-worker")
@RequiredArgsConstructor
public class RuntimeBuildWorkerController {
    private final RuntimeBuildWorkerAuthService authService;
    private final RuntimeBuildWorkerService workerService;

    @PostMapping(value = "/jobs/claim", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<RuntimeBuildJobService.BuildClaim> claim(
            @RequestBody(required = false) String body,
            @RequestHeader("X-SQ-Build-Timestamp") String timestamp,
            @RequestHeader("X-SQ-Build-Nonce") String nonce,
            @RequestHeader("X-SQ-Build-Signature") String signature) {
        String raw = body == null ? "" : body;
        String path = "/api/v2/runtime-build-worker/jobs/claim";
        authService.verify("POST", path, raw, timestamp, nonce, signature);
        return Result.ok(workerService.claim(raw));
    }

    @PostMapping(value = "/jobs/{jobNo}/heartbeat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<RuntimeBuildJob> heartbeat(
            @PathVariable String jobNo,
            @RequestBody(required = false) String body,
            @RequestHeader("X-SQ-Build-Lease") String leaseToken,
            @RequestHeader("X-SQ-Build-Timestamp") String timestamp,
            @RequestHeader("X-SQ-Build-Nonce") String nonce,
            @RequestHeader("X-SQ-Build-Signature") String signature) {
        String raw = body == null ? "" : body;
        String path = "/api/v2/runtime-build-worker/jobs/" + jobNo + "/heartbeat";
        authService.verify("POST", path, raw, timestamp, nonce, signature);
        return Result.ok(workerService.heartbeat(jobNo, leaseToken));
    }

    @PostMapping(value = "/jobs/{jobNo}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<RuntimeBuildJob> complete(
            @PathVariable String jobNo,
            @RequestBody String body,
            @RequestHeader("X-SQ-Build-Lease") String leaseToken,
            @RequestHeader("X-SQ-Build-Timestamp") String timestamp,
            @RequestHeader("X-SQ-Build-Nonce") String nonce,
            @RequestHeader("X-SQ-Build-Signature") String signature) {
        String path = "/api/v2/runtime-build-worker/jobs/" + jobNo + "/complete";
        authService.verify("POST", path, body, timestamp, nonce, signature);
        return Result.ok(workerService.complete(jobNo, leaseToken, body));
    }
}
