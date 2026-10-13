package com.febridge.model;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ModelMapper {
    List<Map<String, Object>> providers();
    Map<String, Object> provider(@Param("id") UUID id);
    int insertProvider(
        @Param("id") UUID id,
        @Param("name") String name,
        @Param("type") String type,
        @Param("url") String url,
        @Param("cipher") String cipher,
        @Param("enabled") boolean enabled
    );
    int updateProvider(
        @Param("id") UUID id,
        @Param("name") String name,
        @Param("cipher") String cipher,
        @Param("enabled") boolean enabled
    );
    List<Map<String, Object>> models();
    Map<String, Object> model(@Param("id") UUID id);
    int insertModel(
        @Param("id") UUID id,
        @Param("providerId") UUID providerId,
        @Param("code") String code,
        @Param("name") String name,
        @Param("tools") boolean tools,
        @Param("parameters") String parameters,
        @Param("enabled") boolean enabled
    );
    int updateModel(
        @Param("id") UUID id,
        @Param("providerId") UUID providerId,
        @Param("code") String code,
        @Param("name") String name,
        @Param("tools") boolean tools,
        @Param("parameters") String parameters,
        @Param("enabled") boolean enabled
    );
}
