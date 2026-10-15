package com.febridge.knowledge;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeMapper {
    List<Map<String, Object>> bases(Map<String, Object> args);
    Map<String, Object> base(Map<String, Object> args);
    int createBase(Map<String, Object> args);
    int insertDocument(Map<String, Object> args);
    List<Map<String, Object>> documents(Map<String, Object> args);
    Map<String, Object> document(Map<String, Object> args);
    int retry(Map<String, Object> args);
    int deleteDocument(Map<String, Object> args);
    Map<String, Object> claim(Map<String, Object> args);
    int insertChunk(Map<String, Object> args);
    int removeChunks(Map<String, Object> args);
    int finish(Map<String, Object> args);
    List<Map<String, Object>> chunks(Map<String, Object> args);
    List<Map<String, Object>> bound(Map<String, Object> args);
    boolean hasReady(Map<String, Object> args);
    int clearBindings(Map<String, Object> args);
    int bind(Map<String, Object> args);
}
