package com.febridge.tool;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ToolMapper {
    List<Map<String, Object>> servers();
    Map<String, Object> server(@Param("id") UUID id);
    int saveServer(
        @Param("id") UUID id,
        @Param("name") String name,
        @Param("endpoint") String endpoint,
        @Param("cipher") String cipher,
        @Param("enabled") boolean enabled,
        @Param("timeout") int timeout,
        @Param("limit") int limit,
        @Param("authType") String authType,
        @Param("authHeader") String authHeader
    );
    List<Map<String, Object>> tools();
    Map<String, Object> tool(@Param("id") UUID id);
    int upsertTool(
        @Param("id") UUID id,
        @Param("serverId") UUID serverId,
        @Param("name") String name,
        @Param("description") String description,
        @Param("schema") String schema
    );
    int disableMissing(@Param("serverId") UUID serverId, @Param("names") String names);
    int updatePolicy(@Param("id") UUID id, @Param("enabled") boolean enabled);
    List<Map<String, Object>> grants(@Param("agentId") UUID agentId);
    List<Map<String, Object>> boundServers(@Param("agentId") UUID agentId);
    int removeServerGrants(@Param("agentId") UUID agentId);
    int grantServer(@Param("agentId") UUID agentId, @Param("serverId") UUID serverId);
    int audit(
        @Param("id") UUID id,
        @Param("agentId") UUID agentId,
        @Param("requestId") UUID requestId,
        @Param("callId") String callId,
        @Param("toolId") UUID toolId,
        @Param("name") String name,
        @Param("fields") String fields
    );
    int auditFields(@Param("id") UUID id, @Param("fields") String fields);
    int finishAudit(
        @Param("id") UUID id,
        @Param("status") String status,
        @Param("size") int size,
        @Param("code") String code,
        @Param("duration") long duration
    );
    List<Map<String, Object>> audits(
        @Param("agentId") UUID agentId,
        @Param("requestId") UUID requestId
    );
}
