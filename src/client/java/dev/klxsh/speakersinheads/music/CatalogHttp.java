package dev.klxsh.speakersinheads.music;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.net.ssl.HttpsURLConnection;

/** Only fixed provider zones can be fetched, including every redirect. */
final class CatalogHttp {
    static final Set<String> JAMENDO = Set.of("jamendo.com", "jamendo-music.com");
    private static final Semaphore REQUESTS = new Semaphore(4);

    private CatalogHttp() {}

    static URI validate(URI uri, Set<String> zones) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("Use a permitted HTTPS provider URL.");
        }
        String domain = host.toLowerCase(Locale.ROOT);
        if (zones.stream().noneMatch(zone -> domain.equals(zone) || domain.endsWith("." + zone))) {
            throw new IllegalArgumentException("The host is outside the approved provider domains.");
        }
        return uri;
    }

    static boolean publicAddress(InetAddress address) {
        byte[] b = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        if (b.length == 4) {
            int a = Byte.toUnsignedInt(b[0]), second = Byte.toUnsignedInt(b[1]);
            int third = Byte.toUnsignedInt(b[2]);
            return a > 0 && a < 224 && a != 127 && !(a == 100 && second >= 64 && second <= 127)
                    && !(a == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    && !(a == 192 && ((second == 0 && (third == 0 || third == 2)) || (second == 88 && third == 99)))
                    && !(a == 203 && second == 0 && third == 113);
        }
        // Currently allocated global-unicast IPv6 only. Reject transition/Teredo and documentation prefixes.
        return (b[0] & 0xe0) == 0x20
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01 && (b[2] & 255) < 2)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01
                && (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8);
    }

    static byte[] bytes(URI uri, Set<String> zones, int limit) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        copy(uri, zones, limit, bytes);
        return bytes.toByteArray();
    }

    static byte[] spotifyTrack(String id, String token) throws IOException {
        if (!id.matches("[A-Za-z0-9]{22}") || !token.matches("[A-Za-z0-9._~+/-]{1,4096}=*")) {
            throw new IOException("Invalid Spotify authorization or track identifier.");
        }
        if (!REQUESTS.tryAcquire()) throw new MusicCatalog.RequestException("Too many music requests. Try again shortly.");
        try {
            var out = new java.io.ByteArrayOutputStream();
            transfer(URI.create("https://api.spotify.com/v1/tracks/" + id), Set.of("api.spotify.com"),
                    256 * 1024, out, ignored -> {}, token);
            return out.toByteArray();
        } finally {
            REQUESTS.release();
        }
    }

    static void copy(URI first, Set<String> zones, int limit, java.io.OutputStream output) throws IOException {
        copy(first, zones, limit, output, ignored -> {});
    }

    static void copy(URI first, Set<String> zones, int limit, java.io.OutputStream output,
                     java.util.function.IntConsumer progress) throws IOException {
        if (!REQUESTS.tryAcquire()) throw new MusicCatalog.RequestException("Too many music requests. Try again shortly.");
        try {
            transfer(first, zones, limit, output, progress, null);
        } finally {
            REQUESTS.release();
        }
    }

    private static void transfer(URI first, Set<String> zones, int limit, java.io.OutputStream output,
                                 java.util.function.IntConsumer progress, String spotifyToken) throws IOException {
        URI uri = first;
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        for (int redirect = 0; redirect <= 3; redirect++) {
            check(deadline);
            validate(uri, zones);
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!publicAddress(address)) throw new IOException("Provider resolved to a non-public address.");
            }
            var connection = (HttpsURLConnection) uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.setRequestProperty("User-Agent", "SpeakersInHeads/0.7.4 (Minecraft client)");
                if (spotifyToken != null) connection.setRequestProperty("Authorization", "Bearer " + spotifyToken);
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (spotifyToken != null || location == null || redirect == 3) {
                        throw new IOException("Provider redirect limit reached.");
                    }
                    uri = uri.resolve(location);
                    continue;
                }
                if (status == 429) throw new MusicCatalog.RequestException("Provider rate limit reached. Try again later.");
                if (status != 200) throw new MusicCatalog.RequestException("Provider returned HTTP " + status + ".");
                long expected = connection.getContentLengthLong();
                if (expected > limit) throw new IOException("Provider response is too large.");
                try (InputStream input = connection.getInputStream()) {
                    byte[] chunk = new byte[16384];
                    int total = 0;
                    for (int size; (size = input.read(chunk)) != -1;) {
                        check(deadline);
                        if (size > limit - total) throw new IOException("Provider response is too large.");
                        output.write(chunk, 0, size);
                        total += size;
                        progress.accept(expected > 0 ? (int) Math.min(99, total * 100L / expected) : -total);
                    }
                    check(deadline);
                    if (total == 0 || (expected >= 0 && total != expected)) {
                        throw new IOException("Provider transfer was incomplete.");
                    }
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Provider redirect limit reached.");
    }

    private static void check(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled.");
        if (System.nanoTime() >= deadline) throw new IOException("Provider request timed out.");
    }
}
