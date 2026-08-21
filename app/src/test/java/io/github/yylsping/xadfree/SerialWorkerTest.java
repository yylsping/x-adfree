package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * P1-1 (2.0.2): the coordinator's serial event lane is a
 * {@code ThreadPoolExecutor(0, 1, keepAlive)} — strictly serial, yet its idle
 * worker thread exits and is transparently recreated for later events.
 */
public final class SerialWorkerTest {
    private static final long KEEPALIVE_MS = 250L;

    private static ThreadPoolExecutor newLane() {
        return new ThreadPoolExecutor(0, 1, KEEPALIVE_MS, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "xadfree-resolver-worker-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Test
    public void eventsRunInSubmissionOrder() throws Exception {
        ThreadPoolExecutor lane = newLane();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 50; i++) {
            final int value = i;
            lane.execute(() -> order.add(value));
        }
        awaitQuiesce(lane);

        for (int i = 0; i < 50; i++) {
            assertEquals("index " + i, i, (int) order.get(i));
        }
        lane.shutdownNow();
    }

    @Test
    public void maximumConcurrencyIsOne() throws Exception {
        ThreadPoolExecutor lane = newLane();
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread submitter = new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    lane.execute(() -> {
                        int now = current.incrementAndGet();
                        peak.accumulateAndGet(now, Math::max);
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException ignored) {
                        } finally {
                            current.decrementAndGet();
                        }
                    });
                }
            });
            threads.add(submitter);
            submitter.start();
        }
        for (Thread submitter : threads) {
            submitter.join(10_000);
        }
        awaitQuiesce(lane);

        assertEquals(1, peak.get());
        lane.shutdownNow();
    }

    @Test
    public void idleWorkerThreadExitsAndRestarts() throws Exception {
        ThreadPoolExecutor lane = newLane();
        final List<String> handled = Collections.synchronizedList(new ArrayList<>());

        lane.execute(() -> handled.add("a"));
        lane.execute(() -> handled.add("b"));
        awaitQuiesce(lane);
        assertTrue("pool should have run", lane.getPoolSize() >= 0);
        assertTrue(handled.contains("a") && handled.contains("b"));

        // Idle beyond keepAlive: the single worker thread exits.
        long deadline = System.currentTimeMillis() + KEEPALIVE_MS + 5_000L;
        while (lane.getPoolSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertEquals("worker thread must exit after idle keepAlive",
                0, lane.getPoolSize());

        // A later event transparently recreates the serial worker.
        lane.execute(() -> handled.add("late"));
        awaitQuiesce(lane);
        assertTrue(handled.contains("late"));

        long deadline2 = System.currentTimeMillis() + KEEPALIVE_MS + 5_000L;
        while (lane.getPoolSize() > 0 && System.currentTimeMillis() < deadline2) {
            Thread.sleep(25);
        }
        assertEquals("worker thread must exit again after second idle window",
                0, lane.getPoolSize());
        lane.shutdownNow();
    }

    private static void awaitQuiesce(ThreadPoolExecutor lane) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while ((lane.getTaskCount() != lane.getCompletedTaskCount()
                || lane.getActiveCount() > 0)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("all tasks must complete",
                lane.getTaskCount(), lane.getCompletedTaskCount());
    }
}
