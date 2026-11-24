package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DistanceIndependentGroupSyncTest {
    @Test
    void fourNativePrefillReadsRemainSequentialWithAStationaryPlaybackClock() throws Exception {
        int rate = 48_000;
        AtomicLong clock = new AtomicLong();
        GroupRingBroadcastStream ring = new GroupRingBroadcastStream(new Ramp(rate, rate * 3), clock::get, 0);
        try {
            ring.ready().get(3, TimeUnit.SECONDS);
            for (int member = 0; member < 4; member++) {
                GroupMemberAudioStream stream = ring.createMemberStream();
                for (int slot = 0; slot < 4; slot++) {
                    ByteBuffer pcm = stream.read(rate * 2); // Minecraft requests one second per slot
                    assertTrue(pcm.isDirect());
                    assertEquals(0, pcm.position());
                    assertEquals(rate / 10 * 2, pcm.remaining());
                    assertEquals(sample(slot * rate / 10), pcm.getShort(0));
                    assertEquals(sample((slot + 1) * rate / 10 - 1), pcm.getShort(pcm.limit() - 2));
                }
            }
        } finally {
            ring.closeAll();
        }
    }

    @Test
    void rejoiningAfterThirtySecondsAlignsOnFirstReadWithoutDisturbingExistingCursor() throws Exception {
        int rate = 48_000;
        AtomicLong clock = new AtomicLong();
        GroupRingBroadcastStream ring = new GroupRingBroadcastStream(new Ramp(rate, rate * 35), clock::get, 0);
        try {
            ring.ready().get(3, TimeUnit.SECONDS);
            GroupMemberAudioStream existing = ring.createMemberStream();
            existing.read(rate * 2);
            GroupMemberAudioStream rejoining = ring.createMemberStream();
            clock.set(rate * 30L);
            awaitFrame(ring, clock.get() + rate / 10);
            ByteBuffer pcm = rejoining.read(rate * 2);
            assertEquals(sample(clock.get()), pcm.getShort(0));
            assertEquals(clock.get() + rate / 10, rejoining.currentFrameIndex());
            assertEquals(rate / 10, existing.currentFrameIndex());
        } finally {
            ring.closeAll();
        }
    }

    @Test
    void seeksInDecoderFramesAndReturnsShortNativeTailThenNull() throws Exception {
        int rate = 48_000;
        long start = rate * 2L + 1;
        GroupRingBroadcastStream ring = new GroupRingBroadcastStream(new Ramp(rate, start + rate / 10 + 3),
                () -> start, start / (double) rate);
        try {
            ring.ready().get(3, TimeUnit.SECONDS);
            GroupMemberAudioStream member = ring.createMemberStream();
            assertEquals(sample(start), member.read(rate * 2).getShort(0));
            ByteBuffer tail = member.read(rate * 2);
            assertEquals(6, tail.remaining());
            assertEquals(sample(start + rate / 10), tail.getShort(0));
            assertNull(member.read(rate * 2));
            assertTrue(member.isEnded());
        } finally {
            ring.closeAll();
        }
    }

    static void awaitFrame(GroupRingBroadcastStream ring, long frame) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (ring.getTotalFrames() < frame && ring.failure() == null && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(ring.getTotalFrames() >= frame, () -> "Decoder stopped at " + ring.getTotalFrames());
    }

    static short sample(long frame) {
        return (short) (frame * 31);
    }

    static class Ramp implements AudioStream {
        private final AudioFormat format;
        private final long lastFrame;
        private long frame;

        Ramp(int rate, long lastFrame) {
            this.format = new AudioFormat(rate, 16, 1, true, false);
            this.lastFrame = lastFrame;
        }

        @Override public AudioFormat getFormat() { return format; }

        @Override
        public ByteBuffer read(int bytes) throws IOException {
            int count = (int) Math.min(bytes / 2, lastFrame - frame);
            ByteBuffer result = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) result.putShort(sample(frame++));
            return result.flip();
        }

        @Override public void close() throws IOException {}
    }
}
