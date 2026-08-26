package com.smartquery.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.service.AlgorithmService;
import com.smartquery.service.MiningService;
import com.smartquery.service.ResourceAccessService;
import com.smartquery.tool.ToolExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class MiningModelToolAuthorizationTest {

    private MiningService miningService;
    private ResourceAccessService resourceAccess;
    private MiningModelTool tool;

    @BeforeEach
    void setUp() {
        miningService = mock(MiningService.class);
        resourceAccess = mock(ResourceAccessService.class);
        tool = new MiningModelTool(
            mock(MiningModelMapper.class), miningService, resourceAccess,
            mock(AlgorithmService.class), new ObjectMapper());
        UserContextHolder.set(new UserContextHolder.UserContext(10L, "alice", "user"));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void agentCannotTrainModelRejectedBySharedAuthorizationService() {
        when(resourceAccess.requireModel(99L))
            .thenThrow(new BusinessException(403, "无权访问该模型"));
        ToolExecutionContext context = new ToolExecutionContext(
            0L, 1L, "trace", "model", null, () -> false, null);

        var result = tool.execute(Map.of("action", "train", "model_id", 99L), context);

        assertFalse(result.success());
        verifyNoInteractions(miningService);
    }
}
