package com.febridge.version;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VersionMapper {
    List<Map<String, Object>> versions(Map<String, Object> args);
    Map<String, Object> version(Map<String, Object> args);
    Map<String, Object> active(Map<String, Object> args);
    List<String> mcpBindings(Map<String, Object> args);
    List<String> knowledgeBindings(Map<String, Object> args);
    int insert(Map<String, Object> args);
    int activate(Map<String, Object> args);
}
