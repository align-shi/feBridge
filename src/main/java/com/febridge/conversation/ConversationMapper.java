package com.febridge.conversation;

import com.febridge.chat.ChatDtos.Answer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationMapper {
    List<Map<String, Object>> sessions(
        @Param("agentId") UUID agentId,
        @Param("ownerId") UUID ownerId
    );
    Map<String, Object> session(
        @Param("id") UUID id,
        @Param("agentId") UUID agentId,
        @Param("ownerId") UUID ownerId
    );
    Map<String, Object> lockSession(
        @Param("id") UUID id,
        @Param("agentId") UUID agentId,
        @Param("ownerId") UUID ownerId
    );
    int createSession(
        @Param("id") UUID id,
        @Param("agentId") UUID agentId,
        @Param("ownerId") UUID ownerId
    );
    List<Map<String, Object>> turns(@Param("id") UUID id);
    List<Map<String, Object>> context(@Param("id") UUID id);
    Map<String, Object> turn(@Param("requestId") UUID requestId);
    int interruptPending(@Param("id") UUID id);
    int insertTurn(
        @Param("id") UUID id,
        @Param("sessionId") UUID sessionId,
        @Param("content") String content
    );
    int start(
        @Param("id") UUID id,
        @Param("requestId") UUID requestId,
        @Param("title") String title
    );
    int finish(@Param("requestId") UUID requestId, @Param("answer") Answer answer);
    int fail(@Param("requestId") UUID requestId, @Param("error") String error);
    int release(@Param("id") UUID id, @Param("requestId") UUID requestId);
}
