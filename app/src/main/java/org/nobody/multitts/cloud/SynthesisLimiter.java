package org.nobody.multitts.cloud;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发与限流调度器：
 * 1. 参考 legado-with-MD3 的 HttpTtsSynthesisLimiter，按引擎 ID 限制 1..8 路并发。
 * 2. 提供固定线程池用于段间并发预合成，消除云端 AI TTS 句间网络等待。
 */
public final class SynthesisLimiter {

    private static final ConcurrentHashMap<String, PermitPool> ENGINE_POOLS = new ConcurrentHashMap<>();
    private static final AtomicInteger THREAD_ID = new AtomicInteger(1);
    private static final ExecutorService PRE_SYNTHESIS_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "CloudTts-Worker-" + THREAD_ID.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

    private SynthesisLimiter() {
    }

    private static final class PermitPool {
        final int maxPermits;
        final Semaphore semaphore;

        PermitPool(int maxPermits) {
            this.maxPermits = maxPermits;
            this.semaphore = new Semaphore(maxPermits, true);
        }
    }

    public static void acquire(String engineKey, int maxConcurrency) throws InterruptedException {
        int clamped = Math.max(1, Math.min(8, maxConcurrency));
        String key = engineKey != null ? engineKey : "default";
        PermitPool pool = ENGINE_POOLS.get(key);
        if (pool == null || pool.maxPermits != clamped) {
            pool = new PermitPool(clamped);
            ENGINE_POOLS.put(key, pool);
        }
        pool.semaphore.acquire();
    }

    public static void release(String engineKey) {
        String key = engineKey != null ? engineKey : "default";
        PermitPool pool = ENGINE_POOLS.get(key);
        if (pool != null) {
            pool.semaphore.release();
        }
    }

    public static <T> Future<T> submitPreSynthesis(final String engineKey, final int maxConcurrency, final Callable<T> task) {
        return PRE_SYNTHESIS_EXECUTOR.submit(() -> {
            acquire(engineKey, maxConcurrency);
            try {
                return task.call();
            } finally {
                release(engineKey);
            }
        });
    }
}
