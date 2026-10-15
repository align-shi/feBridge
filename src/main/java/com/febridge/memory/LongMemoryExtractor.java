package com.febridge.memory;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LongMemoryExtractor {

    // Only complete, explicit first-person declarations are recognized.
    private static final Map<String, String> RULES = Map.of(
        "preference.response_language",
        "以后请用(中文|英文|英语|简体中文|繁体中文)回答",
        "preference.response_style",
        "以后回答请(简洁一些|详细一些|先给结论|使用列表)",
        "default.factory",
        "(?:以后|今后)(?:查询时)?默认(?:查询|使用|看)([\\p{IsHan}A-Za-z0-9_-]{1,30}工厂)",
        "default.business_scope",
        "我的默认业务范围是([\\p{IsHan}A-Za-z0-9_-]{1,40})",
        "fact.role",
        "我的岗位是([\\p{IsHan}A-Za-z0-9_-]{1,30})",
        "fact.responsibility",
        "我长期负责([\\p{IsHan}A-Za-z0-9_-]{1,40})",
        "fact.organization",
        "我所在的部门是([\\p{IsHan}A-Za-z0-9_-]{1,30})"
    );

    public List<LongMemoryDtos.Candidate> extract(String input) {
        if (input.length() > 8000) {
            return List.of();
        }
        var matches = new LinkedHashMap<String, LongMemoryDtos.Candidate>();
        for (String part : input.split("[。！!；;\\n\\r]+")) {
            String sentence = part.strip();
            if (
                sentence
                    .toLowerCase(Locale.ROOT)
                    .matches(
                        "(?s).*(密码|口令|密钥|令牌|身份证|银行卡|password|api.?key|token|secret).*"
                    )
            ) {
                continue;
            }
            for (var rule : RULES.entrySet()) {
                if (Pattern.matches(rule.getValue(), sentence)) {
                    String key = rule.getKey();
                    matches.put(
                        key,
                        new LongMemoryDtos.Candidate(
                            key.substring(0, key.indexOf('.')),
                            key,
                            sentence,
                            sentence
                        )
                    );
                }
            }
        }
        return List.copyOf(matches.values());
    }
}
