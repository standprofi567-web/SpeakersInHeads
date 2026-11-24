package dev.klxsh.speakersinheads.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioMetadataResolverTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        AudioMetadataResolver.clearCache();
    }

    @Test
    void resolvesWavFileDurationAccurately() throws IOException {
        Path wavPath = tempDir.resolve("test_1s.wav");
        createTestWav(wavPath.toFile(), 44100, 1, 1.5); // 1.5 seconds of audio

        double duration = AudioMetadataResolver.getDurationSeconds(wavPath);
        assertEquals(1.5, duration, 0.05);

        // Verify cached retrieval
        double cached = AudioMetadataResolver.getDurationSeconds(wavPath);
        assertEquals(duration, cached, 0.0001);
    }

    @Test
    void returnsZeroForNonExistentFile() {
        Path missing = tempDir.resolve("does_not_exist.wav");
        assertEquals(0.0, AudioMetadataResolver.getDurationSeconds(missing));
    }

    private static void createTestWav(File file, int sampleRate, int channels, double seconds) throws IOException {
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, channels, channels * 2, sampleRate, false);
        int totalFrames = (int) (seconds * sampleRate);
        byte[] pcmData = new byte[totalFrames * format.getFrameSize()];

        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcmData), format, totalFrames)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file);
        }
    }
}
