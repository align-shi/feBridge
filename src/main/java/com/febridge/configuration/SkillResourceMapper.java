package com.febridge.configuration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkillResourceMapper {
    List<Map<String, Object>> resources(@Param("id") UUID id);
    List<String> folders(@Param("id") UUID id);
    int clearFolders(@Param("id") UUID id);
    int saveFolder(@Param("id") UUID id, @Param("name") String name);
    int clear(@Param("id") UUID id);
    int save(
        @Param("id") UUID id,
        @Param("path") String path,
        @Param("content") String content,
        @Param("mediaType") String mediaType,
        @Param("size") int size
    );
}
