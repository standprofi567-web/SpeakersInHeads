package dev.klxsh.speakersinheads.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

public final class MetadataProviders {
    private static final Set<String> SPOTIFY_ART = Set.of("scdn.co", "spotifycdn.com");
    public record Result(TrackMetadata metadata, MusicCatalog.Entry source) {}
    private MetadataProviders() {}

    public static Result resolve(MusicLink link) throws IOException {
        return switch (link.service()) {
            case SPOTIFY -> new Result(spotify(link), null);
            case APPLE -> new Result(apple(link), null);
            case YOUTUBE -> new Result(youtube(link), null);
            case YANDEX -> new Result(TrackMetadata.unavailable("Yandex track recognized. Add a local file or enter its title and artist."), null);
            case JAMENDO -> {
                MusicCatalog.Entry track = MusicCatalog.lookupJamendo(link.id());
                if (track == null) throw new MusicCatalog.RequestException("Jamendo could not find this track.");
                yield new Result(new TrackMetadata(track.title(), track.artist(), "", 0, track.seconds(),
                        image(track.artwork(), CatalogHttp.JAMENDO), track.detail()), track);
            }
            case DIRECT -> new Result(TrackMetadata.unavailable("Audio format and quality are checked during import."), null);
        };
    }

    private static TrackMetadata spotify(MusicLink link) throws IOException {
        String token = System.getenv("SIH_SPOTIFY_ACCESS_TOKEN");
        if (token != null && !token.isBlank()) {
            try {
                var json = com.google.gson.JsonParser.parseString(
                        new String(CatalogHttp.spotifyTrack(link.id(), token.strip()), StandardCharsets.UTF_8));
                if (json.isJsonObject() && link.id().equals(MusicCatalog.string(json.getAsJsonObject(), "id"))) {
                    var metadata = parseSpotifyTrack(json.getAsJsonObject());
                    if (!metadata.title().isBlank()) return metadata;
                }
            } catch (IOException | com.google.gson.JsonParseException ignored) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled.");
                // Public oEmbed is still useful with an expired token or a restricted account.
            }
        }
        var row = fetch("https://open.spotify.com/oembed?url=" + encode(link.original().toString()), Set.of("open.spotify.com"));
        return parseSpotify(row);
    }

    static TrackMetadata parseSpotifyTrack(JsonObject row) {
        var artists = new java.util.ArrayList<String>();
        if (row.has("artists") && row.get("artists").isJsonArray()) {
            for (var artist : row.getAsJsonArray("artists")) {
                if (!artist.isJsonObject()) continue;
                String name = MusicCatalog.string(artist.getAsJsonObject(), "name");
                if (!name.isBlank()) artists.add(name);
            }
        }
        var album = row.has("album") && row.get("album").isJsonObject() ? row.getAsJsonObject("album") : null;
        String date = MusicCatalog.string(album, "release_date");
        int year = date.matches("[0-9]{4}.*") ? Integer.parseInt(date.substring(0, 4)) : 0;
        URI artwork = null;
        if (album != null && album.has("images") && album.get("images").isJsonArray()) {
            for (var candidate : album.getAsJsonArray("images")) {
                if (!candidate.isJsonObject()) continue;
                var url = image(MusicCatalog.string(candidate.getAsJsonObject(), "url"), SPOTIFY_ART);
                if (url != null) artwork = url;
                int width = MusicCatalog.integer(candidate.getAsJsonObject(), "width");
                if (url != null && width > 0 && width <= 300) break;
            }
        }
        return new TrackMetadata(MusicCatalog.string(row, "name"), String.join(", ", artists),
                MusicCatalog.string(album, "name"), year, MusicCatalog.integer(row, "duration_ms") / 1000L, artwork,
                artists.isEmpty() ? "Spotify did not provide the artist." : "");
    }

    static TrackMetadata parseSpotify(JsonObject row) {
        String title = MusicCatalog.string(row, "title").strip();
        String artist = MusicCatalog.string(row, "author_name").strip();
        if (artist.equalsIgnoreCase("Spotify") || artist.equalsIgnoreCase(title)) artist = "";
        String status = title.isBlank() ? "Spotify did not provide a track title."
                : artist.isBlank() ? "Spotify oEmbed omitted the artist. The Spotify Web API can provide it with an access token." : "";
        return new TrackMetadata(title, artist, "", 0, 0,
                image(MusicCatalog.string(row, "thumbnail_url"), SPOTIFY_ART), status);
    }

    private static TrackMetadata apple(MusicLink link) throws IOException {
        var row = fetch("https://itunes.apple.com/lookup?id=" + link.id() + "&country=" + link.market(),
                Set.of("itunes.apple.com"));
        var results = row.getAsJsonArray("results");
        if (results != null) for (var value : results) {
            if (!value.isJsonObject()) continue;
            var item = value.getAsJsonObject();
            if (link.id().equals(MusicCatalog.string(item, "trackId"))) return parseApple(item);
        }
        return TrackMetadata.unavailable("Apple did not return that song in this storefront.");
    }

    static TrackMetadata parseApple(JsonObject item) {
        String date = MusicCatalog.string(item, "releaseDate");
        int year = date.matches("[0-9]{4}.*") ? Integer.parseInt(date.substring(0, 4)) : 0;
        return new TrackMetadata(MusicCatalog.string(item, "trackName"), MusicCatalog.string(item, "artistName"),
                MusicCatalog.string(item, "collectionName"), year, MusicCatalog.integer(item, "trackTimeMillis") / 1000L,
                null, "Metadata from Apple's storefront. Audio requires a separate source.");
    }

    private static TrackMetadata youtube(MusicLink link) throws IOException {
        String key = System.getenv("SIH_YOUTUBE_API_KEY");
        if (key == null || key.isBlank()) {
            return TrackMetadata.unavailable("YouTube link recognized. Set SIH_YOUTUBE_API_KEY for video metadata.");
        }
        var row = fetch("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=" + link.id()
                + "&key=" + encode(key.strip()), Set.of("www.googleapis.com"));
        var items = row.getAsJsonArray("items");
        if (items == null || items.isEmpty()) return TrackMetadata.unavailable("YouTube did not return this video.");
        var item = items.get(0).getAsJsonObject();
        var snippet = item.getAsJsonObject("snippet");
        var details = item.getAsJsonObject("contentDetails");
        long seconds = 0;
        try { seconds = Duration.parse(MusicCatalog.string(details, "duration")).getSeconds(); }
        catch (java.time.format.DateTimeParseException ignored) {}
        return new TrackMetadata(MusicCatalog.string(snippet, "title"), "", "", 0, seconds, null,
                "YouTube video metadata / channel " + MusicCatalog.string(snippet, "channelTitle"));
    }

    private static JsonObject fetch(String url, Set<String> zones) throws IOException {
        JsonElement value = MusicCatalog.json(URI.create(url), zones);
        if (!value.isJsonObject()) throw new MusicCatalog.RequestException("Metadata service sent an invalid reply.");
        return value.getAsJsonObject();
    }

    private static URI image(String url, Set<String> zones) {
        try { return CatalogHttp.validate(URI.create(url), zones); }
        catch (IllegalArgumentException rejected) { return null; }
    }

    public static byte[] loadArtwork(MusicLink link, TrackMetadata metadata) throws IOException {
        if (metadata.artwork() == null) return new byte[0];
        Set<String> domains = switch (link.service()) {
            case SPOTIFY -> SPOTIFY_ART;
            case JAMENDO -> CatalogHttp.JAMENDO;
            default -> Set.of();
        };
        return CatalogHttp.bytes(metadata.artwork(), domains, 512 * 1024);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
