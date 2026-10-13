package com.febridge.tool;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ToolNetworkGuard {

    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    static class State {

        final Semaphore slots = new Semaphore(2);
        long minute = -1;
        int requests;

        synchronized boolean allow() {
            long current = System.currentTimeMillis() / 60000;
            if (minute != current) {
                minute = current;
                requests = 0;
            }
            if (requests >= 30) {
                return false;
            }
            requests++;
            return true;
        }
    }

    public <T> T run(UUID serverId, int seconds, Callable<T> operation) {
        var state = states.computeIfAbsent(serverId, id -> new State());
        if (!state.allow()) {
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "MCP 服务每分钟最多 30 次请求"
            );
        }
        if (!state.slots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MCP 服务并发已达上限");
        }
        Future<T> task;
        try {
            task = workers.submit(() -> {
                try {
                    return operation.call();
                } finally {
                    state.slots.release();
                }
            });
        } catch (RuntimeException exception) {
            state.slots.release();
            throw exception;
        }
        try {
            return task.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "MCP 请求超过总超时限制");
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP 请求已中断");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ResponseStatusException status) {
                throw status;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MCP 请求执行失败");
        }
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }
}
