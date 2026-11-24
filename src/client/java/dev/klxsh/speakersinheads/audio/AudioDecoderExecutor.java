package dev.klxsh.speakersinheads.audio;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scalable background executor for audio decoding tasks.
 * Uses a dynamic thread pool supporting up to 32 concurrent streaming decoders
 * to ensure that 4, 8, 16, or more speakers can play audio simultaneously
 * without worker starvation or task stalling.
 */
final class AudioDecoderExecutor {
    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 32;
    private static final long KEEP_ALIVE_SECONDS = 60L;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            task -> {
                Thread thread = new Thread(task, "SpeakersInHeads-Decoder-" + THREAD_COUNTER.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    private AudioDecoderExecutor() {}

    static void execute(Runnable task) {
        WORKERS.execute(task);
    }

    static <T> CompletableFuture<T> supply(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, WORKERS);
    }

    static int activeCount() {
        return WORKERS.getActiveCount();
    }

    static int poolSize() {
        return WORKERS.getPoolSize();
    }

    static void shutdown() {
        WORKERS.shutdown();
    }
}
