package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.mapper.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MiningServiceAuthorizationTest {

    @Mock MiningModelMapper miningModelMapper;
    @Mock MiningPipelineMapper miningPipelineMapper;
    @Mock ModelExecutionMapper modelExecutionMapper;
    @Mock DataSourceMapper dataSourceMapper;
    @Mock PredictionResultMapper predictionResultMapper;
    @Mock MiningRuntimeClient miningRuntimeClient;
    @Mock DataSourceManager dataSourceManager;
    @Mock AlgorithmService algorithmService;
    @Mock ObjectMapper objectMapper;
    @Mock MiningPredictionService predictionService;
    @Mock PipelineService pipelineService;
    @Mock ConversationEventLogger eventLogger;
    @Mock ResourceAccessService resourceAccess;
    @Mock Executor miningExecutor;

    @InjectMocks MiningService miningService;

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void serviceRejectsUnauthorizedModelBeforeReadingOrMutatingIt() {
        BusinessException forbidden = new BusinessException(403, "无权访问该模型");
        when(resourceAccess.requireModel(12L)).thenThrow(forbidden);

        BusinessException actual = assertThrows(BusinessException.class,
            () -> miningService.updateHyperparameters(12L, "{}"));

        assertEquals(403, actual.getCode());
        verifyNoInteractions(miningModelMapper);
    }

    @Test
    void createFailsClosedWithoutAuthenticatedActor() {
        var model = new com.smartquery.entity.MiningModel();

        BusinessException actual = assertThrows(BusinessException.class,
            () -> miningService.createModel(model));

        assertEquals(401, actual.getCode());
        verifyNoInteractions(miningModelMapper);
    }
}
