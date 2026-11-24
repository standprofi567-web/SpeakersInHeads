package dev.klxsh.speakersinheads.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SystemFolderOpenerTest {
    @TempDir Path temp;

    @Test
    void usesAbsoluteExplorerAndPassesTheWholeFolderAsOneArgument() throws IOException {
        Path system = Files.createDirectory(temp.resolve("Windows With Spaces"));
        Path explorer = Files.createFile(system.resolve("explorer.exe"));
        Path folder = Files.createDirectories(temp.resolve("Profile & Музыка").resolve("music").resolve("speakersinheads"));
        assertEquals(List.of(explorer.toString(), folder.toString()), SystemFolderOpener.explorerCommand(system.toString(), folder));
    }

    @Test
    void missingOrRelativeWindowsRootNeverFallsBackToPath() {
        assertThrows(IOException.class, () -> SystemFolderOpener.explorerCommand(null, temp));
        assertThrows(IOException.class, () -> SystemFolderOpener.explorerCommand("Windows", temp));
        assertThrows(IOException.class, () -> SystemFolderOpener.explorerCommand(temp.toString(), temp));
    }
}
