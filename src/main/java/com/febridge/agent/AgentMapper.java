package com.febridge.agent;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentMapper {
    int insert(AgentDefinition agent);
    AgentDefinition findById(@Param("id") UUID id);
    AgentDefinition lock(@Param("id") UUID id);
    List<AgentDefinition> findRecent();
    int bind(
        @Param("id") UUID id,
        @Param("modelId") UUID modelId,
        @Param("parameters") tools.jackson.databind.JsonNode parameters
    );
}
