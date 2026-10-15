package com.febridge.knowledge;

import static com.febridge.knowledge.KnowledgeDtos.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeStore {

    private final KnowledgeMapper mapper;
    private final com.febridge.agent.AgentService agents;

    public KnowledgeStore(KnowledgeMapper mapper, com.febridge.agent.AgentService agents) {
        this.mapper = mapper;
        this.agents = agents;
    }

    public static Map<String, Object> args(UUID owner, UUID kb) {
        var args = new HashMap<String, Object>();
        args.put("owner", owner.toString());
        if (kb != null) {
            args.put("kb", kb.toString());
        }
        return args;
    }

    public List<Map<String, Object>> list(UUID owner) {
        return mapper.bases(args(owner, null));
    }

    public Map<String, Object> require(UUID owner, UUID kb, boolean write) {
        var row = mapper.base(args(owner, kb));
        if (row == null || (write && !Boolean.TRUE.equals(row.get("editable")))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或无操作权限");
        }
        return row;
    }

    @Transactional
    public Map<String, Object> create(UUID owner, BaseInput input) {
        UUID kb = UUID.randomUUID();
        var args = args(owner, kb);
        args.put("name", input.name().strip());
        args.put("description", input.description());
        args.put("visibility", input.visibility());
        mapper.createBase(args);
        return require(owner, kb, false);
    }

    @Transactional
    public List<Map<String, Object>> documents(UUID owner, UUID kb) {
        require(owner, kb, false);
        return mapper.documents(args(owner, kb));
    }

    @Transactional
    public UUID upload(UUID owner, UUID kb, DocumentInput input, String space) {
        require(owner, kb, true);
        String name = input.name().strip();
        if (
            !(
                name.toLowerCase(Locale.ROOT).endsWith(".txt") ||
                name.toLowerCase(Locale.ROOT).endsWith(".md")
            ) ||
            input.content().indexOf(0) >= 0 ||
            input.content().getBytes(StandardCharsets.UTF_8).length > 524288
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "仅支持512KB以内的UTF-8 TXT/Markdown文档"
            );
        }
        if (mapper.documents(args(owner, kb)).size() >= 200) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "每个知识库最多200份文档");
        }
        UUID document = UUID.randomUUID();
        var args = args(owner, kb);
        args.put("document", document.toString());
        args.put("name", name);
        args.put("content", input.content());
        args.put("space", space);
        mapper.insertDocument(args);
        return document;
    }

    @Transactional
    public void action(UUID owner, UUID kb, UUID document, boolean delete) {
        require(owner, kb, true);
        var args = args(owner, kb);
        args.put("document", document.toString());
        var row = mapper.document(args);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        String status = row.get("status").toString();
        if (delete) {
            if (List.of("processing", "deleting").contains(status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "文档处理中，请稍后再操作");
            }
            mapper.deleteDocument(args);
        } else if (mapper.retry(args) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能重试处理失败的文档");
        }
    }

    public List<Map<String, Object>> bound(UUID owner, UUID agent) {
        var args = args(owner, null);
        args.put("agent", agent.toString());
        return mapper.bound(args);
    }

    @Transactional
    public List<Map<String, Object>> bind(UUID owner, UUID agent, List<UUID> ids) {
        agents.lock(agent);
        for (UUID id : ids) {
            require(owner, id, false);
        }
        var args = args(owner, null);
        args.put("agent", agent.toString());
        mapper.clearBindings(args);
        for (UUID id : new LinkedHashSet<>(ids)) {
            args.put("kb", id.toString());
            mapper.bind(args);
        }
        return bound(owner, agent);
    }

    @Transactional
    public Map<String, Object> claim() {
        return mapper.claim(Map.of("claim", UUID.randomUUID().toString()));
    }

    @Transactional
    public List<UUID> saveChunks(Map<String, Object> job, List<String> texts) {
        var args = jobArgs(job);
        var current = mapper.document(args);
        if (
            current == null ||
            !job.get("claim").equals(current.get("claim")) ||
            !"processing".equals(current.get("status"))
        ) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "文档处理任务已失效");
        }
        mapper.removeChunks(args);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < texts.size(); i++) {
            UUID id = UUID.nameUUIDFromBytes(
                (job.get("id") + ":" + i).getBytes(StandardCharsets.UTF_8)
            );
            ids.add(id);
            args.put("id", id.toString());
            args.put("ordinal", i + 1);
            args.put("content", texts.get(i));
            mapper.insertChunk(args);
        }
        return ids;
    }

    @Transactional
    public void finish(Map<String, Object> job, String status, String error, int count) {
        var args = jobArgs(job);
        args.put("status", status);
        args.put("error", error);
        args.put("count", count);
        if (mapper.finish(args) == 1 && "deleted".equals(status)) {
            mapper.removeChunks(args);
        }
    }

    public boolean hasReady(List<String> bases, String space) {
        return !bases.isEmpty() && mapper.hasReady(Map.of("kbIds", bases, "space", space));
    }

    public List<Map<String, Object>> chunks(
        UUID owner,
        List<String> bases,
        List<String> ids,
        String space
    ) {
        if (ids.isEmpty() || bases.isEmpty()) {
            return List.of();
        }
        var args = args(owner, null);
        args.put("kbIds", bases);
        args.put("ids", ids);
        args.put("space", space);
        return mapper.chunks(args);
    }

    private Map<String, Object> jobArgs(Map<String, Object> job) {
        var args = new HashMap<String, Object>();
        args.put("kb", job.get("kb_id").toString());
        args.put("document", job.get("id").toString());
        args.put("claim", job.get("claim").toString());
        return args;
    }
}
