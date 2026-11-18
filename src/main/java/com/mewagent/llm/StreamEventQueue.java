package com.mewagent.llm;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/** A model stream owns both its producer thread and its open response body. */
public final class StreamEventQueue extends LinkedBlockingQueue<StreamEvent> {
    private final AtomicReference<AutoCloseable> response = new AtomicReference<>();
    private volatile boolean cancelled;
    private volatile Thread producer;

    public StreamEventQueue() { super(64); }

    public void start(Runnable action) {
        producer = Thread.ofVirtual().unstarted(action);
        producer.start();
        if (cancelled) producer.interrupt();
    }

    public void attach(AutoCloseable body) {
        response.set(body);
        if (cancelled) closeResponse();
    }

    public boolean isCancelled() { return cancelled; }

    public void cancel() {
        cancelled = true;
        Thread thread = producer;
        if (thread != null) thread.interrupt();
        closeResponse();
    }

    private void closeResponse() {
        AutoCloseable body = response.getAndSet(null);
        if (body != null) {
            try { body.close(); } catch (Exception ignored) { /* best effort transport cleanup */ }
        }
    }
}
