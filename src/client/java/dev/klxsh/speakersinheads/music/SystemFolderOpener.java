package dev.klxsh.speakersinheads.music;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class SystemFolderOpener {
    private SystemFolderOpener() {}

    static void open(Path folder) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            new ProcessBuilder(explorerCommand(System.getenv("SystemRoot"), folder))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return;
        }

        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("The system has no desktop folder opener");
        }
        Desktop.getDesktop().open(folder.toFile());
    }

    static List<String> explorerCommand(String systemRoot, Path folder) throws IOException {
        if (systemRoot == null || systemRoot.isBlank()) throw new IOException("Windows SystemRoot is unavailable");
        try {
            Path root = Path.of(systemRoot);
            if (!root.isAbsolute()) throw new IOException("Windows SystemRoot is not an absolute path");
            Path explorer = root.resolve("explorer.exe").normalize();
            if (!Files.isRegularFile(explorer)) throw new IOException("Windows Explorer executable was not found");
            return List.of(explorer.toString(), folder.toAbsolutePath().normalize().toString());
        } catch (InvalidPathException invalid) {
            throw new IOException("Windows SystemRoot is invalid", invalid);
        }
    }
}
