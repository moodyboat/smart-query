package com.smartquery.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.entity.DependencyRequest;
import com.smartquery.entity.RuntimeBuildJob;
import com.smartquery.mapper.DependencyRequestMapper;
import com.smartquery.mapper.RuntimeBuildJobMapper;
import com.smartquery.orchestration.RuntimeBuildJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Queues approvals created before the external build-job table existed. */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class RuntimeBuildJobBackfill implements CommandLineRunner {
    private final DependencyRequestMapper requestMapper;
    private final RuntimeBuildJobMapper jobMapper;
    private final RuntimeBuildJobService jobService;

    @Override
    public void run(String... args) {
        for (DependencyRequest request : requestMapper.selectList(
                new LambdaQueryWrapper<DependencyRequest>()
                    .eq(DependencyRequest::getStatus, "APPROVED")
                    .orderByAsc(DependencyRequest::getCreatedAt))) {
            long count = jobMapper.selectCount(new LambdaQueryWrapper<RuntimeBuildJob>()
                .eq(RuntimeBuildJob::getDependencyRequestId, request.getId()));
            if (count > 0) continue;
            try {
                RuntimeBuildJob job = jobService.enqueue(request);
                log.info("[RUNTIME-BUILD] backfilled approved request {} as job {}",
                    request.getRequestNo(), job.getJobNo());
            } catch (Exception error) {
                log.error("[RUNTIME-BUILD] failed to backfill approved request {}: {}",
                    request.getRequestNo(), error.getMessage());
            }
        }
    }
}
