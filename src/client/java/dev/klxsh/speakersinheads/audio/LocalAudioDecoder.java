package dev.klxsh.speakersinheads.audio;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Codec readers only. PCM always enters the Minecraft sound system as an AudioStream. */
final class LocalAudioDecoder {
    private LocalAudioDecoder() {}

    static AudioStream open(Path path) throws IOException {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ogg")) {
            BufferedInputStream input = new BufferedInputStream(Files.newInputStream(path));
            AudioStream stream = null;
            try {
                stream = new JOrbisAudioStream(input);
                validateMinecraftPcm(stream.getFormat(), path);
                return stream;
            } catch (RuntimeException | IOException exception) {
                try {
                    if (stream == null) input.close();
                    else stream.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw new IOException("Could not decode OGG Vorbis file " + path, exception);
            }
        }
        if (!name.endsWith(".mp3") && !name.endsWith(".wav")) {
            throw new IOException("Unsupported audio extension: " + path);
        }

        try {
            AudioInputStream source = AudioSystem.getAudioInputStream(path.toFile());
            AudioFormat sourceFormat = source.getFormat();
            int channels = sourceFormat.getChannels();
            float rate = sourceFormat.getSampleRate();
            if (channels < 1 || channels > 2 || !Float.isFinite(rate) || rate <= 0) {
                source.close();
                throw new IOException("Audio must be mono or stereo with a valid sample rate: " + sourceFormat);
            }
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels,
                    channels * 2, rate, false);
            AudioInputStream decoded;
            if (sourceFormat.matches(pcm)) {
                decoded = source;
            } else {
                if (!AudioSystem.isConversionSupported(pcm, sourceFormat)) {
                    source.close();
                    throw new IOException("No PCM conversion provider for " + sourceFormat);
                }
                decoded = AudioSystem.getAudioInputStream(pcm, source);
            }
            return new JavaSoundPcmStream(decoded, pcm);
        } catch (UnsupportedAudioFileException exception) {
            throw new IOException("No installed decoder accepts " + path, exception);
        }
    }

    private static void validateMinecraftPcm(AudioFormat format, Path path) throws IOException {
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                || format.getSampleSizeInBits() != 16 || format.isBigEndian()
                || format.getChannels() < 1 || format.getChannels() > 2
                || !Float.isFinite(format.getSampleRate()) || format.getSampleRate() <= 0) {
            throw new IOException("Minecraft stream requires mono/stereo little-endian PCM16: " + path + " (" + format + ")");
        }
    }

    private static final class JavaSoundPcmStream implements AudioStream {
        private final AudioInputStream input;
        private final AudioFormat format;

        private JavaSoundPcmStream(AudioInputStream input, AudioFormat format) {
            this.input = input;
            this.format = format;
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public java.nio.ByteBuffer read(int size) throws IOException {
            int frameSize = format.getFrameSize();
            if (size < frameSize) return java.nio.ByteBuffer.allocate(0);
            byte[] bytes = new byte[size - size % frameSize];
            for (int attempt = 0; attempt < 4; attempt++) {
                int count = input.read(bytes);
                if (count < 0) return java.nio.ByteBuffer.allocate(0);
                if (count > 0) return java.nio.ByteBuffer.wrap(bytes, 0, count).slice();
            }
            throw new IOException("Java Sound decoder did not make progress");
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
