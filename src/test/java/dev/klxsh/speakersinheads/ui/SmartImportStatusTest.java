package dev.klxsh.speakersinheads.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmartImportStatusTest {
    @Test
    void spotifyMetadataAndAnExistingLocalMatchDoNotOfferADownload() {
        var metadata = SmartImportStatus.resolve(true, true, true, false, false, false, false, false);
        assertEquals("Metadata found", metadata.metadata());
        assertEquals(SmartImportStatus.Audio.UNAVAILABLE, metadata.audio());
        var local = SmartImportStatus.resolve(true, true, true, false, false, false, true, false);
        assertEquals(SmartImportStatus.Audio.LIBRARY_MATCH, local.audio());
    }

    @Test
    void offersDownloadOnlyAfterVerifyingDirectAudioOrSelectingALicensedSource() {
        var early = SmartImportStatus.resolve(true, false, false, true, false, false, false, false);
        assertEquals(SmartImportStatus.Audio.CHECKING, early.audio());
        assertEquals("", early.metadata());
        assertEquals(SmartImportStatus.Audio.UNAVAILABLE,
                SmartImportStatus.resolve(true, false, false, false, false, false, false, false).audio());
        assertEquals(SmartImportStatus.Audio.DOWNLOAD,
                SmartImportStatus.resolve(true, false, false, false, true, false, false, false).audio());
        assertEquals(SmartImportStatus.Audio.DOWNLOAD,
                SmartImportStatus.resolve(true, true, true, false, true, false, true, false).audio());
        assertEquals(SmartImportStatus.Audio.LOCAL,
                SmartImportStatus.resolve(true, true, true, false, true, true, false, false).audio());
        assertEquals(SmartImportStatus.Audio.IMPORTED,
                SmartImportStatus.resolve(true, false, false, false, true, false, false, true).audio());
    }
}
