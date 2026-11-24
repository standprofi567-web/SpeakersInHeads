package dev.klxsh.speakersinheads.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dev.klxsh.speakersinheads.audio.DistanceIndependentGroupSyncTest.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupRingBroadcastStreamTest {
    @Test
    void simultaneousMembersReceiveTheSamePcm() throws Exception {
        GroupRingBroadcastStream ring = new GroupRingBroadcastStream(new Ramp(44_100, 8000));
        try {
            ring.ready().get(3, TimeUnit.SECONDS);
            List<CompletableFuture<Short>> readers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                GroupMemberAudioStream member = ring.createMemberStream();
                readers.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        assertEquals(sample(0), member.read(100).getShort(0));
                        return member.read(100).getShort(0);
                    } catch (IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                }));
            }
            for (var reader : readers) assertEquals(sample(50), reader.get(3, TimeUnit.SECONDS));
        } finally {
            ring.closeAll();
        }
    }

    @Test
    void wrapRejectsOverwrittenFramesAndRecoversOnlyTheLaggingMember() throws Exception {
        int rate = 48_000;
        AtomicLong clock = new AtomicLong();
        GroupRingBroadcastStream ring = new GroupRingBroadcastStream(new Ramp(rate, GroupRingBroadcastStream.CAPACITY * 2L),
                clock::get, 0);
        try {
            ring.ready().get(3, TimeUnit.SECONDS);
            GroupMemberAudioStream member = ring.createMemberStream();
            member.read(100);
            clock.set(GroupRingBroadcastStream.CAPACITY + 5000L);
            awaitFrame(ring, clock.get() + rate);
            ByteBuffer dest = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0, ring.copyPcm(0, 10, dest));
            long boundary = GroupRingBroadcastStream.CAPACITY;
            assertEquals(10, ring.copyPcm(boundary - 5, 10, dest));
            dest.flip();
            for (long frame = boundary - 5; frame < boundary + 5; frame++) assertEquals(sample(frame), dest.getShort());
            assertEquals(sample(clock.get()), member.read(100).getShort(0));
        } finally {
            ring.closeAll();
        }
    }

    @Test
    void stopClosesDecoderOnceOnWorkerEvenWhileReadIsInProgress() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        GroupRingBroadcastStream ring = new GroupRingBroadcastStream(new Ramp(44_100, 8000) {
            @Override public ByteBuffer read(int bytes) throws IOException {
                reading.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Read was not released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                return super.read(bytes);
            }

            @Override public void close() {
                closes.incrementAndGet();
                closed.countDown();
            }
        });
        try {
            assertTrue(reading.await(3, TimeUnit.SECONDS));
            GroupMemberAudioStream member = ring.createMemberStream();
            ring.closeAll();
            ring.closeAll();
            assertNull(member.read(1024));
            assertEquals(0, closes.get());
        } finally {
            ring.closeAll();
            release.countDown();
        }
        assertTrue(closed.await(3, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> ring.ready().get(3, TimeUnit.SECONDS));
        assertEquals(1, closes.get());
    }

    @Test
    void decoderFailureBeforePrefillDoesNotProducePlayingStream() throws Exception {
        IOException failed = new IOException("truncated");
        GroupRingBroadcastStream ring = new GroupRingBroadcastStream(new Ramp(44_100, 8000) {
            @Override public ByteBuffer read(int bytes) throws IOException { throw failed; }
        });
        try {
            assertSame(failed, assertThrows(ExecutionException.class, () -> ring.ready().get(3, TimeUnit.SECONDS)).getCause());
            assertSame(failed, assertThrows(IOException.class, () -> ring.createMemberStream().read(1000)));
        } finally {
            ring.closeAll();
        }
    }
}
