package com.paymentflow.sandbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dedicated executor for simulated latency (a test card's {@code latency_ms}, or an
 * {@code inject_latency}/{@code force_timeout} override): the decision itself is
 * evaluated and persisted synchronously and eagerly; only the HTTP response's delivery
 * is deferred, via {@link java.util.concurrent.CompletableFuture#delayedExecutor}. This
 * frees the servlet request thread for the delay's duration instead of blocking it —
 * deliberately not {@code Thread.sleep} on the calling thread, which would hold a
 * Tomcat worker idle for up to the platform's maximum injectable latency
 * (10s) under concurrent slow-card requests.
 */
@Configuration
public class SandboxAsyncConfig {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService sandboxDelayExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "sandbox-delay-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newScheduledThreadPool(4, threadFactory);
    }
}
