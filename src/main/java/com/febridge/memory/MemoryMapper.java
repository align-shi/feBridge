package com.febridge.memory;

import com.febridge.chat.ChatDtos.Usage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MemoryMapper {
    Map<String, Object> summary(@Param("sessionId") UUID sessionId);
    List<Map<String, Object>> candidates(
        @Param("sessionId") UUID sessionId,
        @Param("after") long after,
        @Param("through") long through
    );
    long pending(
        @Param("sessionId") UUID sessionId,
        @Param("after") long after,
        @Param("through") long through
    );
    int save(
        @Param("sessionId") UUID sessionId,
        @Param("requestId") UUID requestId,
        @Param("ownerId") UUID ownerId,
        @Param("expectedVersion") int expectedVersion,
        @Param("content") String content,
        @Param("through") long through,
        @Param("coveredTurns") long coveredTurns,
        @Param("truncated") boolean truncated,
        @Param("model") String model,
        @Param("usage") Usage usage,
        @Param("duration") long duration
    );
}
