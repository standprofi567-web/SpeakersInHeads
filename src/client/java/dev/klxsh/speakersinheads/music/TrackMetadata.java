package dev.klxsh.speakersinheads.music;

import java.net.URI;

public record TrackMetadata(String title, String artist, String album, int year, long seconds, URI artwork, String status) {
    public static TrackMetadata unavailable(String status) {
        return new TrackMetadata("", "", "", 0, 0, null, status);
    }
}
