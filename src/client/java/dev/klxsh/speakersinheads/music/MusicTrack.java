package dev.klxsh.speakersinheads.music;

import java.nio.file.Path;

public record MusicTrack(Path path, String title, boolean available, Source source) {
    public MusicTrack(Path path, String title) {
        this(path, title, path != null && java.nio.file.Files.isRegularFile(path), Source.EXTERNAL);
    }

    public MusicTrack(Path path, String title, boolean available) {
        this(path, title, available, Source.EXTERNAL);
    }

    public String fileName() {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    public enum Source { FOLDER, EXTERNAL }
}
