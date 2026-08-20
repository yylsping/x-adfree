package io.github.yylsping.xadfree;

import java.util.HashMap;
import java.util.Map;

/** Manual Scheduler for JVM tests; timers only fire when told to. */
public final class FakeScheduler implements Scheduler {
    public static final class Timer {
        public final String token;
        public final Runnable runnable;
        public final long delayMillis;

        Timer(String token, Runnable runnable, long delayMillis) {
            this.token = token;
            this.runnable = runnable;
            this.delayMillis = delayMillis;
        }
    }

    private final Map<String, Timer> timers = new HashMap<>();

    @Override
    public synchronized void postDelayed(String token, Runnable runnable, long delayMillis) {
        cancel(token);
        timers.put(token, new Timer(token, runnable, delayMillis));
    }

    @Override
    public synchronized void cancel(String token) {
        timers.remove(token);
    }

    public synchronized boolean isScheduled(String token) {
        return timers.containsKey(token);
    }

    public synchronized Timer timer(String token) {
        return timers.get(token);
    }

    /** Runs (and removes) the pending runnable for a token. */
    public synchronized void fire(String token) {
        Timer timer = timers.remove(token);
        if (timer != null) {
            timer.runnable.run();
        }
    }
}
