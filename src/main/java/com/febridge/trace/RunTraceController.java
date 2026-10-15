package com.febridge.trace;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class RunTraceController {

    private final RunTrace trace;

    public RunTraceController(RunTrace trace) {
        this.trace = trace;
    }

    @GetMapping("/api/agents/{id}/runs/{requestId}/events")
    public List<RunTrace.Event> events(@PathVariable UUID id, @PathVariable UUID requestId) {
        trace.requireAccess(id, requestId);
        return trace.events(id, requestId);
    }
}
