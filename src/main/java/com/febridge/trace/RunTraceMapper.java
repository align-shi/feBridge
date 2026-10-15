package com.febridge.trace;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RunTraceMapper {
    int append(
        @Param("agentId") UUID agentId,
        @Param("requestId") UUID requestId,
        @Param("kind") String kind,
        @Param("summary") String summary,
        @Param("details") String details,
        @Param("ownerId") UUID ownerId
    );
    int ownedRequest(
        @Param("agentId") UUID agentId,
        @Param("requestId") UUID requestId,
        @Param("ownerId") UUID ownerId
    );
    List<Map<String, Object>> events(
        @Param("agentId") UUID agentId,
        @Param("requestId") UUID requestId
    );
}
