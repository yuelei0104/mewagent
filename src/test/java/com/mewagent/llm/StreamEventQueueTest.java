package com.mewagent.llm;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StreamEventQueueTest {
    @Test void lateResponseIsClosedWhenCancellationWinsTheRace() {
        var events = new StreamEventQueue();
        var closed = new AtomicInteger();
        events.cancel(); events.attach(closed::incrementAndGet); events.cancel();
        assertEquals(1, closed.get());
    }

    @Test void cancellationUnblocksAProducerWaitingOnAFullQueue() throws Exception {
        var events = new StreamEventQueue();
        for (int i = 0; i < 64; i++) events.add(new StreamEvent.TextDelta("x"));
        var stopped = new CountDownLatch(1);
        events.start(() -> {
            try { events.put(new StreamEvent.TextDelta("blocked")); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { stopped.countDown(); }
        });
        events.cancel();
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
    }
}
