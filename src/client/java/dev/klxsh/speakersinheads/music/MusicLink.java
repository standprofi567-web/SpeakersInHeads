package dev.klxsh.speakersinheads.music;

import java.net.URI;
import java.util.Locale;

/** Canonical service links are metadata inputs. Only DIRECT may be passed to audio transport. */
public record MusicLink(Service service, URI original, String id, String market) {
    public enum Service {
        SPOTIFY("Spotify"), APPLE("Apple Music"), YANDEX("Yandex Music"), YOUTUBE("YouTube Music"),
        JAMENDO("Jamendo"), DIRECT("Direct audio");
        private final String label;
        Service(String label) { this.label = label; }
        public String label() { return label; }
    }

    public static MusicLink parse(String value) {
        URI url;
        try { url = DirectAudioHttp.validate(URI.create(value.strip())); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Enter a valid public HTTP or HTTPS URL."); }
        String host = url.getHost().toLowerCase(Locale.ROOT), path = url.getPath();
        if (host.equals("open.spotify.com") && path.matches("/(?:intl-[A-Za-z-]+/)?track/[A-Za-z0-9]{22}/?")) {
            String id = tail(path);
            return new MusicLink(Service.SPOTIFY, URI.create("https://open.spotify.com/track/" + id), id, "");
        }
        if (host.equals("music.apple.com") && path.matches("/[A-Za-z]{2}/(?:album|song)/.+")) {
            String id = path.contains("/album/") ? parameter(url, "i") : tail(path);
            if (id.matches("[0-9]{1,17}")) {
                String market = path.substring(1, 3).toLowerCase(Locale.ROOT);
                String track = path.contains("/album/") ? "?i=" + id : "";
                return new MusicLink(Service.APPLE, URI.create("https://music.apple.com" + url.getRawPath() + track),
                        id, market);
            }
        }
        if ((host.equals("music.yandex.ru") || host.equals("music.yandex.com"))
                && path.matches("/album/[0-9]+/track/[0-9]+/?")) {
            return new MusicLink(Service.YANDEX, URI.create("https://" + host + path), tail(path), "");
        }
        if (zone(host, "youtube.com") || host.equals("youtu.be")) {
            String id = host.equals("youtu.be") ? tail(path) : parameter(url, "v");
            if (id.matches("[A-Za-z0-9_-]{11}")) {
                String site = host.equals("music.youtube.com") ? host : "www.youtube.com";
                return new MusicLink(Service.YOUTUBE, URI.create("https://" + site + "/watch?v=" + id), id, "");
            }
        }
        if (zone(host, "jamendo.com")) {
            String id = path.matches("/track/[0-9]+(?:/[^/]+)?/?") ? path.split("/")[2]
                    : path.matches("/download/track/[0-9]+/(mp31|mp32|ogg|flac)/?") ? path.split("/")[3] : "";
            if (!id.isBlank()) {
                return new MusicLink(Service.JAMENDO, URI.create("https://www.jamendo.com/track/" + id), id, "");
            }
        }
        if (directBlocked(url)) throw new IllegalArgumentException("Use the full track-page link for this service.");
        return new MusicLink(Service.DIRECT, url, "", "");
    }

    static boolean directBlocked(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return zone(host, "spotify.com") || zone(host, "spotify.link") || zone(host, "scdn.co")
                || zone(host, "apple.com") || zone(host, "mzstatic.com") || zone(host, "applemusic.com")
                || zone(host, "youtube.com") || zone(host, "youtu.be") || zone(host, "youtube-nocookie.com")
                || zone(host, "googlevideo.com") || zone(host, "ytimg.com")
                || zone(host, "yandex.ru") || zone(host, "yandex.com") || zone(host, "yandex.net") || zone(host, "ya.ru")
                || zone(host, "jamendo.com") || zone(host, "jamendo-music.com") || zone(host, "jamen.do");
    }

    private static boolean zone(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String tail(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private static String parameter(URI uri, String name) {
        if (uri.getRawQuery() == null) return "";
        for (String part : uri.getRawQuery().split("&")) {
            if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        }
        return "";
    }
}
