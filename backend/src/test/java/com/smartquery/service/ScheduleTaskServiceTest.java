package com.smartquery.service;

import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ScheduleTask;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.ScheduleTaskMapper;
import com.smartquery.orchestration.VersionCatalogService;
import com.smartquery.entity.FlowDefinition;
import com.smartquery.entity.FlowVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleTaskServiceTest {
    private final ScheduleTaskMapper taskMapper = mock(ScheduleTaskMapper.class);
    private final MiningModelMapper modelMapper = mock(MiningModelMapper.class);
    private final ResourceAccessService resourceAccess = mock(ResourceAccessService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final VersionCatalogService versionCatalogService = mock(VersionCatalogService.class);
    private final ScheduleTaskService service = new ScheduleTaskService(
        taskMapper, modelMapper, resourceAccess, roleService, versionCatalogService, new ObjectMapper());

    @Test
    void createsIndependentActivePredictionTask() {
        MiningModel model = publishedModel();
        when(resourceAccess.requireModel(7L)).thenReturn(model);

        service.create(new ScheduleTaskService.ScheduleTaskCommand(
            "每日风险预测", 7L, "PREDICT", "0 6 * * *",
            "CUSTOMER_INPUT", "status = 'active'", "RISK_OUTPUT", "ACTIVE"));

        ArgumentCaptor<ScheduleTask> captor = ArgumentCaptor.forClass(ScheduleTask.class);
        verify(taskMapper).insert(captor.capture());
        ScheduleTask task = captor.getValue();
        assertEquals("每日风险预测", task.getName());
        assertEquals("PREDICT", task.getScheduleMode());
        assertEquals("ACTIVE", task.getStatus());
        assertNotNull(task.getNextRunAt());
        assertEquals("9", task.getOwnerUserId());
    }

    @Test
    void pausingTaskClearsNextRunButKeepsDefinition() {
        ScheduleTask task = new ScheduleTask();
        task.setId(18L);
        task.setModelId(7L);
        task.setDeleted(0);
        task.setStatus("ACTIVE");
        task.setCronExpression("0 6 * * *");
        when(taskMapper.selectById(18L)).thenReturn(task);
        when(resourceAccess.requireModel(7L)).thenReturn(publishedModel());
        when(modelMapper.selectById(7L)).thenReturn(publishedModel());

        service.changeStatus(18L, "PAUSED");

        assertEquals("PAUSED", task.getStatus());
        assertNull(task.getNextRunAt());
        verify(taskMapper).updateById(task);
    }

    @Test
    void createsScheduledPublishedFlowWithJsonInput() {
        FlowVersion version = new FlowVersion();
        version.setId(31L); version.setFlowId(12L); version.setVersionNo(4); version.setStatus("PUBLISHED");
        FlowDefinition flow = new FlowDefinition();
        flow.setId(12L); flow.setName("重复支付识别模型"); flow.setOwnerUserId("9");
        when(versionCatalogService.requireFlowVersion(31L)).thenReturn(version);
        when(versionCatalogService.requireFlow(12L)).thenReturn(flow);

        service.create(new ScheduleTaskService.ScheduleTaskCommand(
            "每小时重复支付检测", "FLOW", null, 31L, "FLOW", "0 * * * *",
            null, null, null, "{\"records\":[]}", "ACTIVE"));

        ArgumentCaptor<ScheduleTask> captor = ArgumentCaptor.forClass(ScheduleTask.class);
        verify(taskMapper).insert(captor.capture());
        ScheduleTask task = captor.getValue();
        assertEquals("FLOW", task.getTaskType());
        assertEquals(31L, task.getFlowVersionId());
        assertNull(task.getModelId());
        assertEquals("{\"records\":[]}", task.getInputPayload());
        assertNotNull(task.getNextRunAt());
    }

    private MiningModel publishedModel() {
        MiningModel model = new MiningModel();
        model.setId(7L);
        model.setName("客户风险模型");
        model.setStatus("published");
        model.setVersion(3);
        model.setUserId("9");
        model.setDeleted(0);
        return model;
    }
}
