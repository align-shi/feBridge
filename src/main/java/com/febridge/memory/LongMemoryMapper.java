package com.febridge.memory;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LongMemoryMapper {
    int ensure(Map<String, Object> args);
    Map<String, Object> policy(Map<String, Object> args);
    Map<String, Object> lockPolicy(Map<String, Object> args);
    int setPolicy(Map<String, Object> args);
    List<Map<String, Object>> entries(Map<String, Object> args);
    int manualSave(Map<String, Object> args);
    int change(Map<String, Object> args);
    int enqueue(Map<String, Object> args);
    Map<String, Object> claim(Map<String, Object> args);
    Map<String, Object> source(Map<String, Object> args);
    int automaticSave(Map<String, Object> args);
    int finishJob(Map<String, Object> args);
    List<Map<String, Object>> jobs(Map<String, Object> args);
}
