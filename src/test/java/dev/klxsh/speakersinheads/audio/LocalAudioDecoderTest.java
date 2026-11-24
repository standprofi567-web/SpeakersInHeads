package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAudioDecoderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void decodesPcmWavAsMinecraftPcm() throws Exception {
        Path wav = createWav(temporaryDirectory.resolve("fixture.wav"));
        try (AudioStream stream = LocalAudioDecoder.open(wav)) {
            assertMinecraftPcm(stream.getFormat());
            ByteBuffer first = stream.read(1024);
            assertTrue(first.remaining() > 0);
            assertTrue(stream.read(1024).remaining() > 0);
        }
    }

    @Test
    void decodesMinecraftBundledVorbisFixture() throws Exception {
        try (InputStream resource = getClass().getClassLoader()
                .getResourceAsStream("assets/fabric-sound-api-v1/sounds/empty.ogg")) {
            assertNotNull(resource, "Fabric Sound API empty.ogg fixture must be on the client test classpath");
            Path ogg = temporaryDirectory.resolve("fixture.ogg");
            Files.copy(resource, ogg);
            try (AudioStream stream = LocalAudioDecoder.open(ogg)) {
                assertMinecraftPcm(stream.getFormat());
                ByteBuffer first = stream.read(8192);
                assertFalse(first.isReadOnly());
            }
        }
    }

    @Test
    void decodesSyntheticSilentMp3FramesAsMinecraftPcm() throws Exception {
        Path mp3 = temporaryDirectory.resolve("silent.mp3");
        byte[] frame = new byte[417];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xFB; // MPEG-1 Layer III, no CRC
        frame[2] = (byte) 0x90; // 128 kbit/s, 44.1 kHz
        frame[3] = 0x64; // stereo, no padding
        byte[] frames = new byte[frame.length * 6];
        for (int offset = 0; offset < frames.length; offset += frame.length) {
            System.arraycopy(frame, 0, frames, offset, frame.length);
        }
        Files.write(mp3, frames);
        try (AudioStream stream = LocalAudioDecoder.open(mp3)) {
            assertMinecraftPcm(stream.getFormat());
            assertTrue(stream.read(8192).remaining() > 0);
            assertTrue(stream.read(8192).remaining() > 0);
        }
    }

    @Test
    void rejectsCorruptMp3Cleanly() throws IOException {
        Path mp3 = temporaryDirectory.resolve("broken.mp3");
        Files.write(mp3, new byte[]{0, 1, 2, 3, 4, 5});
        assertThrows(IOException.class, () -> LocalAudioDecoder.open(mp3));
    }

    private Path createWav(Path destination) throws Exception {
        int sampleRate = 44100;
        int samples = 2048;
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16,
                1, 2, sampleRate, false);
        byte[] pcm = new byte[samples * 2];
        for (int sample = 0; sample < samples; sample++) {
            short tone = (short) (Math.sin(2.0 * Math.PI * 440.0 * sample / sampleRate) * 8000);
            pcm[sample * 2] = (byte) tone;
            pcm[sample * 2 + 1] = (byte) (tone >> 8);
        }
        try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(pcm), format, samples)) {
            assertTrue(AudioSystem.write(input, AudioFileFormat.Type.WAVE, destination.toFile()) > 0);
        }
        return destination;
    }

    private static void assertMinecraftPcm(AudioFormat format) {
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
        assertEquals(16, format.getSampleSizeInBits());
        assertTrue(format.getChannels() == 1 || format.getChannels() == 2);
        assertFalse(format.isBigEndian());
        assertTrue(format.getSampleRate() > 0);
    }
}
