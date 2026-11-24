package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerGroupAudioTest {

    @Test
    void testGroupWithThreePcmQueueStreams() throws Exception {
        int totalFrames = 44100 * 5; // 5 seconds
        AudioStream mockSource = new AudioStream() {
            private int frameIndex = 0;

            @Override
            public AudioFormat getFormat() {
                return new AudioFormat(44100, 16, 1, true, false);
            }

            @Override
            public ByteBuffer read(int size) {
                int framesToRead = Math.min(size / 2, totalFrames - frameIndex);
                if (framesToRead <= 0) return ByteBuffer.allocate(0);

                ByteBuffer buf = ByteBuffer.allocate(framesToRead * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < framesToRead; i++) {
                    buf.putShort((short) 1000); // Constant audible signal
                    frameIndex++;
                }
                buf.flip();
                return buf;
            }

            @Override
            public void close() {}
        };

        GroupRingBroadcastStream broadcast = new GroupRingBroadcastStream(mockSource);

        broadcast.ready().get(3, TimeUnit.SECONDS);
        AudioStream m1 = broadcast.createMemberStream();
        AudioStream m2 = broadcast.createMemberStream();
        AudioStream m3 = broadcast.createMemberStream();

        PcmQueueAudioStream q1 = new PcmQueueAudioStream(m1, "member 1");
        PcmQueueAudioStream q2 = new PcmQueueAudioStream(m2, "member 2");
        PcmQueueAudioStream q3 = new PcmQueueAudioStream(m3, "member 3");

        // Wait for prebuffer
        CompletableFuture.allOf(q1.prebuffered(), q2.prebuffered(), q3.prebuffered())
                .get(3, TimeUnit.SECONDS);

        // Read chunks like Minecraft SoundEngine does
        ByteBuffer b1 = q1.read(4096);
        ByteBuffer b2 = q2.read(4096);
        ByteBuffer b3 = q3.read(4096);

        assertNotNull(b1);
        assertNotNull(b2);
        assertNotNull(b3);
        assertTrue(b1.hasRemaining(), "Member 1 should have audio data");
        assertTrue(b2.hasRemaining(), "Member 2 should have audio data");
        assertTrue(b3.hasRemaining(), "Member 3 should have audio data");

        // Check that samples are NOT silence
        short s1 = b1.getShort(0);
        short s2 = b2.getShort(0);
        short s3 = b3.getShort(0);

        System.out.println("s1=" + s1 + ", s2=" + s2 + ", s3=" + s3);
        assertTrue(s1 != 0, "Member 1 must not be silent");
        assertTrue(s2 != 0, "Member 2 must not be silent");
        assertTrue(s3 != 0, "Member 3 must not be silent");

        // Test stopping / closing
        q1.close();
        q2.close();
        q3.close();
        broadcast.closeAll();
    }
}
