package com.febridge.configuration;

import static com.febridge.configuration.ConfigurationDtos.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConfigurationMapper {
    int profile(@Param("id") UUID id, @Param("input") Profile input);
    List<Map<String, Object>> skills();
    Map<String, Object> skill(@Param("id") UUID id);
    int saveSkill(@Param("id") UUID id, @Param("input") SkillInput input);
    List<Map<String, Object>> boundSkills(@Param("id") UUID id);
    int clearSkills(@Param("id") UUID id);
    int bindSkill(
        @Param("id") UUID id,
        @Param("skillId") UUID skillId,
        @Param("position") int position
    );
    String rules(@Param("id") UUID id);
    int saveRules(@Param("id") UUID id, @Param("rules") String rules);
}
