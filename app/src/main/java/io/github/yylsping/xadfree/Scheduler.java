package io.github.yylsping.xadfree;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

/**
 * Named one-shot timer abstraction over the main-thread Handler. Named tokens
 * let the bootstrap deadline and the witness deadline be cancelled
 * independently, which is what keeps the 20s bootstrap watchdog from killing a
 * legitimate 30s probe window (P0-2). JVM tests substitute a manual fake.
 */
interface Scheduler {

    /** Schedules {@code runnable} after {@code delayMillis}; replaces token. */
    void postDelayed(String token, Runnable runnable, long delayMillis);

    /** Cancels the pending runnable for {@code token} if any; idempotent. */
    void cancel(String token);

    /** Production implementation on the main looper. */
    final class HandlerScheduler implements Scheduler {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Map<String, Runnable> pending = new HashMap<>();

        @Override
        public synchronized void postDelayed(String token, Runnable runnable, long delayMillis) {
            cancel(token);
            Runnable wrapped = runnable;
            pending.put(token, wrapped);
            handler.postDelayed(wrapped, delayMillis);
        }

        @Override
        public synchronized void cancel(String token) {
            Runnable runnable = pending.remove(token);
            if (runnable != null) {
                handler.removeCallbacks(runnable);
            }
        }
    }
}
