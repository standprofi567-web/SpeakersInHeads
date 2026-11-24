package dev.klxsh.speakersinheads.music;

import com.sun.nio.file.ExtendedOpenOption;
import dev.klxsh.speakersinheads.audio.AudioStreamFactory;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class ManagedFileReadiness {
    enum State { READY, SETTLING, INVALID }
    private record Stamp(long size, java.nio.file.attribute.FileTime modified, Object key) {}
    private record Checked(Stamp stamp, State state) {}
    private static final long QUIET_MILLIS = 1250;
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    private final Map<Path, Checked> checked = new HashMap<>();

    State check(Path path, long now) {
        try {
            var stamp = stamp(path);
            var known = checked.get(path);
            if (known != null && known.stamp().equals(stamp)) return known.state();
            if (now - stamp.modified().toMillis() < QUIET_MILLIS) return State.SETTLING;
            State state;
            try (SeekableByteChannel guard = Files.newByteChannel(path, readOptions())) {
                state = probe(path);
                if (guard.size() != stamp.size() || !stamp.equals(stamp(path))) return State.SETTLING;
            } catch (IOException busy) {
                return State.SETTLING;
            }
            checked.put(path, new Checked(stamp, state));
            return state;
        } catch (IOException missing) {
            checked.remove(path);
            return State.INVALID;
        }
    }

    void forgetMissing() { checked.keySet().removeIf(path -> !Files.isRegularFile(path)); }

    void recordImported(Path path) throws IOException {
        checked.put(path, new Checked(stamp(path), State.READY));
    }

    private static State probe(Path path) {
        try {
            AudioStreamFactory.validateLocalFile(path);
            return State.READY;
        } catch (IOException | RuntimeException invalid) {
            return State.INVALID;
        }
    }

    private static OpenOption[] readOptions() {
        return WINDOWS ? new OpenOption[]{StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_WRITE}
                : new OpenOption[]{StandardOpenOption.READ};
    }

    private static Stamp stamp(Path path) throws IOException {
        var a = Files.readAttributes(path, BasicFileAttributes.class);
        return new Stamp(a.size(), a.lastModifiedTime(), a.fileKey());
    }
}
