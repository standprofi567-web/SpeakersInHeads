package dev.klxsh.speakersinheads.music;

import dev.klxsh.speakersinheads.SpeakersInHeadsClient;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches the exact duration (in seconds) for MP3, WAV, and OGG Vorbis audio tracks.
 */
public final class AudioMetadataResolver {
    private static final Map<Path, Double> DURATION_CACHE = new ConcurrentHashMap<>();

    private AudioMetadataResolver() {}

    /**
     * Returns the cached or computed duration in seconds for the given track file, or 0.0 if unknown.
     */
    public static double getDurationSeconds(Path path) {
        if (path == null || !Files.isRegularFile(path)) return 0.0;
        Path normalized = path.toAbsolutePath().normalize();
        return DURATION_CACHE.computeIfAbsent(normalized, AudioMetadataResolver::computeDuration);
    }

    private static double computeDuration(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        File file = path.toFile();

        try {
            if (name.endsWith(".wav")) {
                AudioFileFormat aff = AudioSystem.getAudioFileFormat(file);
                long frames = aff.getFrameLength();
                AudioFormat af = aff.getFormat();
                if (frames > 0 && af != null && af.getFrameRate() > 0) {
                    return frames / (double) af.getFrameRate();
                }
            } else if (name.endsWith(".mp3")) {
                AudioFileFormat aff = AudioSystem.getAudioFileFormat(file);
                Map<String, Object> props = aff.properties();
                if (props != null && props.containsKey("duration")) {
                    Object val = props.get("duration");
                    if (val instanceof Number n) {
                        return n.doubleValue() / 1_000_000.0;
                    }
                }
                // Fallback: estimate from frame length or size if property absent
                long frames = aff.getFrameLength();
                AudioFormat af = aff.getFormat();
                if (frames > 0 && af != null && af.getFrameRate() > 0) {
                    return frames / (double) af.getFrameRate();
                }
            } else if (name.endsWith(".ogg")) {
                double oggDur = parseOggVorbisDuration(file);
                if (oggDur > 0.0) return oggDur;
            }
        } catch (Exception exception) {
            SpeakersInHeadsClient.LOGGER.debug("Could not determine duration for audio file {}", path, exception);
        }

        return 0.0;
    }

    /**
     * Efficiently reads the Vorbis sample rate from the beginning and the granule_pos
     * from the last Ogg page at the end of the file to determine the exact duration without
     * decoding the entire audio stream.
     */
    static double parseOggVorbisDuration(File file) {
        if (!file.isFile() || file.length() < 64) return 0.0;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();

            // 1. Read first 64 bytes to locate sample rate in Vorbis identification header
            byte[] header = new byte[(int) Math.min(length, 128)];
            raf.readFully(header);

            long sampleRate = 0;
            for (int i = 0; i < header.length - 15; i++) {
                // Vorbis id header starts with 0x01 'v' 'o' 'r' 'b' 'i' 's'
                if (header[i] == 1 && header[i + 1] == 'v' && header[i + 2] == 'o'
                        && header[i + 3] == 'r' && header[i + 4] == 'b' && header[i + 5] == 'i' && header[i + 6] == 's') {
                    // channels at i + 11 (1 byte), sample_rate at i + 12 (4 bytes little-endian)
                    int srOffset = i + 12;
                    sampleRate = (header[srOffset] & 0xFFL)
                            | ((header[srOffset + 1] & 0xFFL) << 8)
                            | ((header[srOffset + 2] & 0xFFL) << 16)
                            | ((header[srOffset + 3] & 0xFFL) << 24);
                    break;
                }
            }

            if (sampleRate <= 0) sampleRate = 44100; // safe fallback

            // 2. Scan the last 64 KB of the file for the final 'OggS' page header
            int scanSize = (int) Math.min(length, 65536);
            long scanStart = length - scanSize;
            raf.seek(scanStart);
            byte[] tail = new byte[scanSize];
            raf.readFully(tail);

            long lastGranulePos = -1;
            for (int i = tail.length - 14; i >= 0; i--) {
                if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S') {
                    // Granule position is at offset 6 (8 bytes little-endian)
                    long granule = (tail[i + 6] & 0xFFL)
                            | ((tail[i + 7] & 0xFFL) << 8)
                            | ((tail[i + 8] & 0xFFL) << 16)
                            | ((tail[i + 9] & 0xFFL) << 24)
                            | ((tail[i + 10] & 0xFFL) << 32)
                            | ((tail[i + 11] & 0xFFL) << 40)
                            | ((tail[i + 12] & 0xFFL) << 48)
                            | ((tail[i + 13] & 0xFFL) << 56);

                    if (granule > 0) {
                        lastGranulePos = granule;
                        break;
                    }
                }
            }

            if (lastGranulePos > 0) {
                return lastGranulePos / (double) sampleRate;
            }
        } catch (IOException ignored) {}

        return 0.0;
    }

    public static void clearCache() {
        DURATION_CACHE.clear();
    }
}
