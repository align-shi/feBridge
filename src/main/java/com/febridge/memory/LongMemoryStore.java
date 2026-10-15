package com.febridge.memory;

import static com.febridge.memory.LongMemoryDtos.*;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LongMemoryStore {

    private final LongMemoryMapper mapper;
    private final com.febridge.agent.AgentService agents;

    public LongMemoryStore(LongMemoryMapper mapper, com.febridge.agent.AgentService agents) {
        this.mapper = mapper;
        this.agents = agents;
    }

    public static Map<String, Object> scope(UUID owner, UUID agent) {
        var args = new HashMap<String, Object>();
        args.put("owner", owner.toString());
        args.put("agent", agent.toString());
        return args;
    }

    @Transactional
    public Map<String, Object> view(UUID owner, UUID agent) {
        var args = scope(owner, agent);
        mapper.ensure(args);
        return Map.of(
            "enabled",
            mapper.policy(args).get("enabled"),
            "mode",
            mapper.policy(args).get("mode"),
            "items",
            mapper.entries(args),
            "jobs",
            mapper.jobs(args)
        );
    }

    @Transactional
    public void policy(UUID owner, UUID agent, boolean enabled) {
        setMode(owner, agent, enabled ? MemoryMode.CROSS_SESSION : MemoryMode.CONTEXT);
    }

    @Transactional
    public MemoryMode mode(UUID owner, UUID agent) {
        var args = scope(owner, agent);
        mapper.ensure(args);
        return MemoryMode.valueOf(mapper.lockPolicy(args).get("mode").toString());
    }

    @Transactional
    public void setMode(UUID owner, UUID agent, MemoryMode mode) {
        agents.lock(agent);
        var args = scope(owner, agent);
        mapper.ensure(args);
        mapper.lockPolicy(args);
        args.put("enabled", mode == MemoryMode.CROSS_SESSION);
        args.put("mode", mode.name());
        if (mapper.setPolicy(args) != 1) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "对话正在生成，请结束后再切换记忆模式"
            );
        }
    }

    @Transactional
    public void create(UUID owner, UUID agent, Input input) {
        var args = scope(owner, agent);
        mapper.ensure(args);
        mapper.lockPolicy(args);
        if (mapper.entries(args).size() >= 100) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "最多保存100条长期记忆");
        }
        args.put("id", UUID.randomUUID().toString());
        args.put("category", input.category());
        args.put("key", input.key());
        args.put("content", input.content().strip());
        args.put("enabled", input.enabled());
        args.put("expires", input.expiresAt() == null ? null : input.expiresAt().toString());
        if (mapper.manualSave(args) != 1) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "编码已存在或曾被删除，请使用其他编码"
            );
        }
    }

    @Transactional
    public void edit(UUID owner, UUID agent, UUID id, Edit input, boolean deleted) {
        var args = scope(owner, agent);
        mapper.ensure(args);
        mapper.lockPolicy(args);
        args.put("id", id.toString());
        args.put("content", input.content().strip());
        args.put("enabled", input.enabled());
        args.put("expires", input.expiresAt() == null ? null : input.expiresAt().toString());
        args.put("version", input.version());
        args.put("deleted", deleted);
        if (mapper.change(args) != 1) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "记忆不存在或已更新，请刷新后重试"
            );
        }
    }

    @Transactional
    public void enqueue(UUID owner, UUID agent, UUID request) {
        var args = scope(owner, agent);
        mapper.ensure(args);
        args.put("request", request.toString());
        mapper.enqueue(args);
    }

    public String recall(UUID owner, UUID agent, String question) {
        var args = scope(owner, agent);
        var policy = mapper.policy(args);
        if (policy == null || !Boolean.TRUE.equals(policy.get("enabled"))) {
            return "";
        }
        var matches = mapper
            .entries(args)
            .stream()
            .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
            .filter(
                row ->
                    row.get("expires_at") == null ||
                    instant(row.get("expires_at")).isAfter(Instant.now())
            )
            .filter(
                row ->
                    !"fact".equals(row.get("category")) ||
                    relevant(question, row.get("content").toString())
            )
            .limit(8)
            .toList();
        var text = new StringBuilder();
        for (var row : matches) {
            if (text.length() + row.get("content").toString().length() > 3000) {
                break;
            }
            text.append("- ")
                .append(row.get("memory_key"))
                .append(": ")
                .append(row.get("content"))
                .append("\n");
        }
        return text.toString();
    }

    static boolean relevant(String question, String content) {
        String q = question.toLowerCase(Locale.ROOT);
        String c = content.toLowerCase(Locale.ROOT);
        for (String word : q.split("[^a-z0-9_]+")) {
            if (word.length() >= 3 && c.contains(word)) {
                return true;
            }
        }
        for (int i = 0; i + 1 < q.length(); i++) {
            if (
                Character.UnicodeScript.of(q.charAt(i)) == Character.UnicodeScript.HAN &&
                Character.UnicodeScript.of(q.charAt(i + 1)) == Character.UnicodeScript.HAN &&
                c.contains(q.substring(i, i + 2))
            ) {
                return true;
            }
        }
        return false;
    }

    private static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((java.time.OffsetDateTime) value).toInstant();
    }

    @Transactional
    public Map<String, Object> claim() {
        return mapper.claim(Map.of("claim", UUID.randomUUID().toString()));
    }

    public Map<String, Object> source(Map<String, Object> job) {
        return mapper.source(jobArgs(job));
    }

    @Transactional
    public void finish(Map<String, Object> job, List<Candidate> candidates, String error) {
        var args = jobArgs(job);
        var policy = mapper.lockPolicy(args);
        boolean allowed =
            policy != null &&
            Boolean.TRUE.equals(policy.get("enabled")) &&
            Objects.equals(policy.get("generation"), job.get("generation"));
        args.put("status", !allowed ? "skipped" : error == null ? "completed" : "failed");
        args.put("model", "local-rules-v1");
        args.put("prompt", 0L);
        args.put("completion", 0L);
        args.put("total", 0L);
        args.put("error", error);
        if (mapper.finishJob(args) != 1 || !allowed || error != null) {
            return;
        }
        var existing = mapper.entries(args);
        var keys = new HashSet<String>();
        for (var row : existing) {
            keys.add(row.get("memory_key").toString());
        }
        int count = existing.size();
        for (var candidate : candidates) {
            if (!keys.contains(candidate.key()) && count >= 100) {
                continue;
            }
            args.put("id", UUID.randomUUID().toString());
            args.put("category", candidate.category());
            args.put("key", candidate.key());
            args.put("content", candidate.content());
            args.put("evidence", candidate.evidence());
            if (mapper.automaticSave(args) == 1 && keys.add(candidate.key())) {
                count++;
            }
        }
    }

    private Map<String, Object> jobArgs(Map<String, Object> job) {
        var args = scope(
            UUID.fromString(job.get("owner_id").toString()),
            UUID.fromString(job.get("agent_id").toString())
        );
        args.put("request", job.get("request_id").toString());
        args.put("session", job.get("session_id").toString());
        args.put("claim", job.get("claim").toString());
        args.put("sourceOrder", job.get("source_order"));
        return args;
    }
}
