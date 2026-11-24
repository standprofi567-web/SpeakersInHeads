package dev.klxsh.speakersinheads.ui;

record SmartImportStatus(String metadata, Audio audio) {
    enum Audio {
        UNCHECKED("Audio: analyze a link or choose a file"),
        CHECKING("Audio: checking source…"),
        UNAVAILABLE("Audio unavailable from this link"),
        LIBRARY_MATCH("Audio available in your library"),
        DOWNLOAD("Audio available for download"),
        LOCAL("Audio: local file selected"),
        IMPORTED("Audio available in your library");

        final String label;
        Audio(String label) { this.label = label; }
    }

    static SmartImportStatus resolve(boolean analyzedLink, boolean providerLink, boolean hasMetadata, boolean checking,
                                     boolean verifiedSource, boolean localFile, boolean libraryMatch, boolean imported) {
        String metadata = hasMetadata ? "Metadata found"
                : providerLink ? (checking ? "Checking metadata…" : "Metadata unavailable") : "";
        Audio audio;
        if (imported) audio = Audio.IMPORTED;
        else if (localFile) audio = Audio.LOCAL;
        else if (verifiedSource) audio = Audio.DOWNLOAD;
        else if (libraryMatch) audio = Audio.LIBRARY_MATCH;
        else if (checking) audio = Audio.CHECKING;
        else if (analyzedLink) audio = Audio.UNAVAILABLE;
        else audio = Audio.UNCHECKED;
        return new SmartImportStatus(metadata, audio);
    }
}
