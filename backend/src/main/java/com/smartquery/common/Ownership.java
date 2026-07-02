package com.smartquery.common;

import com.smartquery.entity.Conversation;
import com.smartquery.entity.MiningModel;
import com.smartquery.mapper.ConversationMapper;
import com.smartquery.mapper.MiningModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 多租户归属校验：判定当前登录用户是否拥有某会话或某挖掘模型。
 * <p>
 * 设计：
 * <ul>
 *   <li>admin 角色直通（管理员可见全部）</li>
 *   <li>其他角色按实体上的 user_id 字段比对</li>
 *   <li>实体 user_id 为 null（历史数据）时，普通用户拒绝访问，避免越权</li>
 * </ul>
 * 由 AuthInterceptor 写入 ThreadLocal，ThreadLocalCleanupFilter 清理。
 */
@Component
@RequiredArgsConstructor
public class Ownership {

    private final ConversationMapper conversationMapper;
    private final MiningModelMapper miningModelMapper;

    public boolean isAdmin() {
        UserContextHolder.UserContext ctx = UserContextHolder.get();
        return ctx != null && "admin".equals(ctx.role());
    }

    public String currentUserIdString() {
        UserContextHolder.UserContext ctx = UserContextHolder.get();
        return ctx == null || ctx.userId() == null ? null : ctx.userId().toString();
    }

    public boolean conversation(Long conversationId) {
        if (conversationId == null) return false;
        if (isAdmin()) return true;
        String uid = currentUserIdString();
        if (uid == null) return false;
        Conversation c = conversationMapper.selectById(conversationId);
        return c != null && uid.equals(c.getUserId());
    }

    public boolean model(Long modelId) {
        if (modelId == null) return false;
        if (isAdmin()) return true;
        String uid = currentUserIdString();
        if (uid == null) return false;
        MiningModel m = miningModelMapper.selectById(modelId);
        return modelOwnedBy(m, uid);
    }

    public boolean modelOwnedBy(MiningModel m) {
        if (m == null) return false;
        if (isAdmin()) return true;
        String uid = currentUserIdString();
        if (uid == null) return false;
        return modelOwnedBy(m, uid);
    }

    private boolean modelOwnedBy(MiningModel m, String uid) {
        if (m == null) return false;
        return uid.equals(m.getUserId());
    }
}
