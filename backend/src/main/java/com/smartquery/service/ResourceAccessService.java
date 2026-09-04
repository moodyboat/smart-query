package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.Conversation;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.MiningPipeline;
import com.smartquery.entity.ModelExecution;
import com.smartquery.mapper.ConversationMapper;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.MiningPipelineMapper;
import com.smartquery.mapper.ModelExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Single authorization boundary for user-owned conversations, mining models,
 * pipelines and their training history. Controllers and LLM tools must use this
 * service instead of reproducing (or omitting) owner predicates.
 */
@Service
@RequiredArgsConstructor
public class ResourceAccessService {

    private final ConversationMapper conversationMapper;
    private final MiningModelMapper miningModelMapper;
    private final MiningPipelineMapper miningPipelineMapper;
    private final ModelExecutionMapper modelExecutionMapper;
    private final RoleService roleService;

    public String currentUserId() {
        return UserContextHolder.require().userId().toString();
    }

    public boolean isAdmin() {
        return roleService.currentUserHas(PermissionCodes.RESOURCE_ACCESS_ALL);
    }

    public void requireAdmin() {
        roleService.requireCurrentUser(PermissionCodes.RESOURCE_ACCESS_ALL, "无权限执行全局管理操作");
    }

    public void requirePermission(String permissionCode, String message) {
        roleService.requireCurrentUser(permissionCode, message);
    }

    public boolean hasPermission(String permissionCode) {
        return roleService.currentUserHas(permissionCode);
    }

    public Conversation requireConversation(Long id) {
        Conversation conversation = id == null ? null : conversationMapper.selectById(id);
        if (conversation == null) {
            throw new BusinessException(404, "会话不存在: " + id);
        }
        requireOwner(conversation.getUserId(), "无权访问该会话");
        return conversation;
    }

    public List<Conversation> listConversations() {
        LambdaQueryWrapper<Conversation> query = new LambdaQueryWrapper<Conversation>()
            .orderByDesc(Conversation::getCreatedAt);
        if (!isAdmin()) query.eq(Conversation::getUserId, currentUserId());
        return conversationMapper.selectList(query);
    }

    public MiningModel requireModel(Long id) {
        MiningModel model = id == null ? null : miningModelMapper.selectById(id);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new BusinessException(404, "模型不存在: " + id);
        }
        requireOwner(model.getUserId(), "无权访问该模型");
        return model;
    }

    public List<MiningModel> listModels(Long dataSourceId, String status) {
        LambdaQueryWrapper<MiningModel> query = new LambdaQueryWrapper<MiningModel>()
            .eq(MiningModel::getDeleted, 0)
            .orderByDesc(MiningModel::getCreatedAt);
        if (!isAdmin() && !hasPermission(PermissionCodes.MODEL_REVIEW)) {
            query.eq(MiningModel::getUserId, currentUserId());
        }
        if (dataSourceId != null) query.eq(MiningModel::getDataSourceId, dataSourceId);
        if (status != null && !status.isBlank()) query.eq(MiningModel::getStatus, status);
        return miningModelMapper.selectList(query);
    }

    public MiningModel findModelByPipeline(Long pipelineId) {
        requirePipeline(pipelineId);
        MiningModel model = miningModelMapper.selectOne(
            new LambdaQueryWrapper<MiningModel>()
                .eq(MiningModel::getPipelineId, pipelineId)
                .eq(MiningModel::getDeleted, 0)
                .last("LIMIT 1"));
        if (model != null) requireOwner(model.getUserId(), "无权访问该模型");
        return model;
    }

    public List<ModelExecution> listModelExecutions(Long modelId, int limit) {
        requireModel(modelId);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return modelExecutionMapper.selectList(
            new LambdaQueryWrapper<ModelExecution>()
                .eq(ModelExecution::getModelId, modelId)
                .orderByDesc(ModelExecution::getCreatedAt)
                .last("LIMIT " + safeLimit));
    }

    public ModelExecution requireModelExecution(Long modelId, Long executionId) {
        requireModel(modelId);
        ModelExecution execution = executionId == null ? null : modelExecutionMapper.selectById(executionId);
        if (execution == null || !modelId.equals(execution.getModelId())) {
            throw new BusinessException(404, "执行记录不存在或不属于该模型: " + executionId);
        }
        return execution;
    }

    public MiningPipeline requirePipeline(Long id) {
        MiningPipeline pipeline = id == null ? null : miningPipelineMapper.selectById(id);
        if (pipeline == null || Integer.valueOf(1).equals(pipeline.getDeleted())) {
            throw new BusinessException(404, "流水线不存在: " + id);
        }
        requireOwner(pipeline.getUserId(), "无权访问该流水线");
        return pipeline;
    }

    public List<MiningPipeline> listPipelines(Long dataSourceId) {
        LambdaQueryWrapper<MiningPipeline> query = new LambdaQueryWrapper<MiningPipeline>()
            .eq(MiningPipeline::getDeleted, 0)
            .orderByDesc(MiningPipeline::getCreatedAt);
        if (!isAdmin()) query.eq(MiningPipeline::getUserId, currentUserId());
        if (dataSourceId != null) query.eq(MiningPipeline::getDataSourceId, dataSourceId);
        return miningPipelineMapper.selectList(query);
    }

    private void requireOwner(String ownerId, String message) {
        if (isAdmin()) return;
        if (ownerId == null || !currentUserId().equals(ownerId)) {
            throw new BusinessException(403, message);
        }
    }
}
