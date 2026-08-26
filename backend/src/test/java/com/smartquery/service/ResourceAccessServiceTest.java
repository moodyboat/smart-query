package com.smartquery.service;

import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.Conversation;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.MiningPipeline;
import com.smartquery.mapper.ConversationMapper;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.MiningPipelineMapper;
import com.smartquery.mapper.ModelExecutionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceAccessServiceTest {

    private ConversationMapper conversationMapper;
    private MiningModelMapper modelMapper;
    private MiningPipelineMapper pipelineMapper;
    private ModelExecutionMapper executionMapper;
    private ResourceAccessService access;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        modelMapper = mock(MiningModelMapper.class);
        pipelineMapper = mock(MiningPipelineMapper.class);
        executionMapper = mock(ModelExecutionMapper.class);
        access = new ResourceAccessService(conversationMapper, modelMapper, pipelineMapper, executionMapper);
        UserContextHolder.set(new UserContextHolder.UserContext(10L, "alice", "user"));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void conversationRejectsAnotherUsersResource() {
        Conversation conversation = new Conversation();
        conversation.setId(1L);
        conversation.setUserId("11");
        when(conversationMapper.selectById(1L)).thenReturn(conversation);

        BusinessException error = assertThrows(BusinessException.class,
            () -> access.requireConversation(1L));
        assertEquals(403, error.getCode());
    }

    @Test
    void modelHistoryIsNotQueriedWhenModelIsNotOwned() {
        MiningModel model = new MiningModel();
        model.setId(2L);
        model.setUserId("11");
        model.setDeleted(0);
        when(modelMapper.selectById(2L)).thenReturn(model);

        assertThrows(BusinessException.class, () -> access.listModelExecutions(2L, 20));
        verifyNoInteractions(executionMapper);
    }

    @Test
    void pipelineAllowsItsOwner() {
        MiningPipeline pipeline = new MiningPipeline();
        pipeline.setId(3L);
        pipeline.setUserId("10");
        pipeline.setDeleted(0);
        when(pipelineMapper.selectById(3L)).thenReturn(pipeline);

        assertSame(pipeline, access.requirePipeline(3L));
    }

    @Test
    void historicalNullOwnerIsAdminOnly() {
        MiningModel model = new MiningModel();
        model.setId(4L);
        model.setDeleted(0);
        when(modelMapper.selectById(4L)).thenReturn(model);

        assertThrows(BusinessException.class, () -> access.requireModel(4L));

        UserContextHolder.set(new UserContextHolder.UserContext(1L, "admin", "admin"));
        assertSame(model, access.requireModel(4L));
    }

    @Test
    void missingAsyncIdentityFailsClosed() {
        UserContextHolder.clear();
        BusinessException error = assertThrows(BusinessException.class, access::currentUserId);
        assertEquals(401, error.getCode());
    }

    @Test
    void executionMustBelongToAuthorizedModel() {
        MiningModel model = new MiningModel();
        model.setId(5L);
        model.setUserId("10");
        model.setDeleted(0);
        when(modelMapper.selectById(5L)).thenReturn(model);

        var execution = new com.smartquery.entity.ModelExecution();
        execution.setId(8L);
        execution.setModelId(99L);
        when(executionMapper.selectById(8L)).thenReturn(execution);

        BusinessException error = assertThrows(BusinessException.class,
            () -> access.requireModelExecution(5L, 8L));
        assertEquals(404, error.getCode());
    }

    @Test
    void adminMayAccessHistoricalNullOwnedPipeline() {
        MiningPipeline pipeline = new MiningPipeline();
        pipeline.setId(6L);
        pipeline.setDeleted(0);
        when(pipelineMapper.selectById(6L)).thenReturn(pipeline);
        UserContextHolder.set(new UserContextHolder.UserContext(1L, "admin", "admin"));

        assertSame(pipeline, access.requirePipeline(6L));
    }
}
