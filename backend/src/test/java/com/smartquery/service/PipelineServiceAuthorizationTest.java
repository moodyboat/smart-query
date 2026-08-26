package com.smartquery.service;

import com.smartquery.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class PipelineServiceAuthorizationTest {

    @Mock com.smartquery.mapper.MiningPipelineMapper miningPipelineMapper;
    @Mock com.smartquery.mapper.MiningModelMapper miningModelMapper;
    @Mock com.smartquery.mapper.ModelExecutionMapper modelExecutionMapper;
    @Mock com.smartquery.mapper.DataSourceMapper dataSourceMapper;
    @Mock MiningRuntimeClient miningRuntimeClient;
    @Mock com.smartquery.datasource.DataSourceManager dataSourceManager;
    @Mock AlgorithmService algorithmService;
    @Mock com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock com.smartquery.logging.ConversationEventLogger eventLogger;
    @Mock ResourceAccessService resourceAccess;

    @InjectMocks PipelineService pipelineService;

    @Test
    void executionUsesSharedAuthorizationBoundary() {
        BusinessException forbidden = new BusinessException(403, "无权访问该流水线");
        org.mockito.Mockito.when(resourceAccess.requirePipeline(9L)).thenThrow(forbidden);

        BusinessException actual = assertThrows(BusinessException.class,
            () -> pipelineService.executePipeline(9L));

        assertEquals(403, actual.getCode());
    }
}
