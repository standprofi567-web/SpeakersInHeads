package dev.klxsh.speakersinheads.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioDecoderExecutorTest {

    @Test
    void supportsAtLeast16ConcurrentDecodersWithoutDeadlock() throws Exception {
        int tasksCount = 16;
        CountDownLatch startLatch = new CountDownLatch(tasksCount);
        CountDownLatch continueLatch = new CountDownLatch(1);
        AtomicInteger completedTasks = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < tasksCount; i++) {
            CompletableFuture<Void> future = AudioDecoderExecutor.supply(() -> {
                startLatch.countDown();
                try {
                    // Simulate holding a decoder stream open
                    boolean ok = continueLatch.await(5, TimeUnit.SECONDS);
                    if (ok) {
                        completedTasks.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {}
                return null;
            });
            futures.add(future);
        }

        // All 16 threads must be actively running at the same time
        boolean allStarted = startLatch.await(5, TimeUnit.SECONDS);
        assertTrue(allStarted, "All 16 concurrent decoders must start simultaneously without queue deadlock");

        // Release them
        continueLatch.countDown();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        assertEquals(tasksCount, completedTasks.get(), "All 16 tasks must complete successfully");
    }

    @Test
    void saturationRejectsWorkWithoutRunningItOnCaller() throws Exception {
        CountDownLatch started = new CountDownLatch(32);
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        AtomicInteger executedOverflow = new AtomicInteger();
        try {
            for (int i = 0; i < 32; i++) {
                tasks.add(AudioDecoderExecutor.supply(() -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return null;
                }));
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> AudioDecoderExecutor.execute(executedOverflow::incrementAndGet));
            assertEquals(0, executedOverflow.get());
        } finally {
            release.countDown();
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0])).get(5, TimeUnit.SECONDS);
        }
    }
}
