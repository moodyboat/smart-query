package com.smartquery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartquery.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("SELECT id, role, content, trace_id, created_at " +
            "FROM sq_chat_message " +
            "WHERE conversation_id = #{conversationId} " +
            "ORDER BY created_at ASC")
    List<Map<String, Object>> selectMessagesByConversation(@Param("conversationId") Long conversationId);
}
