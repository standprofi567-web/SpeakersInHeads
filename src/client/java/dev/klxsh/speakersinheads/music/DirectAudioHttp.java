package dev.klxsh.speakersinheads.music;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.function.IntConsumer;

final class DirectAudioHttp {
    static final int LIMIT = 32 * 1024 * 1024;
    private static final Semaphore TRANSFERS = new Semaphore(2);

    private DirectAudioHttp() {}

    static URI validate(URI uri) {
        String scheme = uri.getScheme(), host = uri.getHost();
        if (host == null || host.endsWith(".") || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Use a public HTTP or HTTPS URL without credentials.");
        }
        int port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
        if (uri.getPort() != -1 && uri.getPort() != port) {
            throw new IllegalArgumentException("Use the standard port for HTTP or HTTPS.");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if ((!host.contains(".") && !host.startsWith("[")) || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal")) {
            throw new IllegalArgumentException("Local network URLs are unavailable.");
        }
        if (uri.getRawPath().isEmpty()) {
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            uri = URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + "/" + query);
        }
        return URI.create(uri.toASCIIString());
    }

    static void copy(URI initial, OutputStream out, IntConsumer progress) throws IOException {
        transferWithPermit(initial, out, progress);
    }

    static AuthorizedImport.Preview preview(URI initial) throws IOException {
        return transferWithPermit(initial, null, ignored -> {});
    }

    private static AuthorizedImport.Preview transferWithPermit(URI initial, OutputStream out, IntConsumer progress) throws IOException {
        if (!TRANSFERS.tryAcquire()) throw new MusicCatalog.RequestException("Another audio transfer is still closing.");
        try {
            return transfer(initial, out, progress);
        } finally {
            TRANSFERS.release();
        }
    }

    private static AuthorizedImport.Preview transfer(URI initial, OutputStream out, IntConsumer progress) throws IOException {
        boolean preview = out == null;
        URI uri = initial;
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        for (int redirects = 0; ; redirects++) {
            check(deadline);
            try {
                uri = validate(uri);
                if (MusicLink.directBlocked(uri)) throw new IllegalArgumentException("Music platforms provide metadata only.");
            } catch (IllegalArgumentException invalid) {
                throw new MusicCatalog.RequestException(invalid.getMessage());
            }
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            for (InetAddress address : addresses) {
                if (!CatalogHttp.publicAddress(address)) {
                    throw new MusicCatalog.RequestException("The URL resolved to a non-public address.");
                }
            }
            InetAddress selected = addresses[0];
            // Prefer IPv4 when DNS advertises both families. No later lookup is used for the peer.
            for (var address : addresses) if (address instanceof java.net.Inet4Address) { selected = address; break; }
            try (PinnedProxy proxy = new PinnedProxy(uri, selected);
                 HttpClient client = HttpClient.newBuilder()
                         .proxy(ProxySelector.of(proxy.endpoint()))
                         .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(6)).build()) {
                var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "SpeakersInHeads/0.7.4").GET();
                if (preview && "https".equalsIgnoreCase(uri.getScheme())) builder.header("Range", "bytes=0-4095");
                var request = builder.build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    int status = response.statusCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        var location = response.headers().firstValue("location");
                        if (redirects == 3 || location.isEmpty()) throw new MusicCatalog.RequestException("Redirect limit reached.");
                        URI next = uri.resolve(location.get());
                        if ("https".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
                            throw new MusicCatalog.RequestException("An HTTPS link redirected to an insecure endpoint.");
                        }
                        uri = next;
                        continue;
                    }
                    if (status != 200 && !(preview && status == 206)) {
                        throw new MusicCatalog.RequestException("Audio server returned HTTP " + status + ".");
                    }
                    long expected = status == 206 ? rangeSize(response.headers().firstValue("content-range").orElse(""))
                            : response.headers().firstValueAsLong("content-length").orElse(-1);
                    if (expected > LIMIT) throw new MusicCatalog.RequestException("Audio exceeds 32 MiB.");
                    if (preview) {
                        check(deadline);
                        return previewAudio(body.readNBytes(300), expected);
                    }
                    var data = new byte[16384];
                    int total = 0;
                    for (int count; (count = body.read(data)) != -1;) {
                        check(deadline);
                        if (count > LIMIT - total) throw new MusicCatalog.RequestException("Audio exceeds 32 MiB.");
                        out.write(data, 0, count);
                        total += count;
                        progress.accept(expected > 0 ? (int) Math.min(99, total * 100L / expected) : -total);
                    }
                    if (total == 0 || expected >= 0 && expected != total) {
                        throw new MusicCatalog.RequestException("The audio transfer was incomplete.");
                    }
                    return new AuthorizedImport.Preview("", total);
                }
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new IOException("Import cancelled.");
            }
        }
    }

    static AuthorizedImport.Preview previewAudio(byte[] prefix, long size) throws IOException {
        if (size > LIMIT) throw new MusicCatalog.RequestException("Audio exceeds 32 MiB.");
        String format = AuthorizedImport.detect(prefix).substring(1).toUpperCase(Locale.ROOT);
        if (format.equals("OGG")) {
            int start = prefix.length < 27 ? prefix.length : 27 + Byte.toUnsignedInt(prefix[26]);
            byte[] vorbis = {1, 'v', 'o', 'r', 'b', 'i', 's'};
            if (start + vorbis.length > prefix.length
                    || !java.util.Arrays.equals(prefix, start, start + vorbis.length, vorbis, 0, vorbis.length)) {
                throw new MusicCatalog.RequestException("This Ogg file does not contain supported Vorbis audio.");
            }
        }
        return new AuthorizedImport.Preview(format, size);
    }

    private static long rangeSize(String header) throws IOException {
        // A partial response must start at byte zero to be used as evidence of a file format.
        if (!header.matches("bytes 0-[0-9]+/([0-9]+|\\*)")) {
            throw new MusicCatalog.RequestException("Audio server returned an invalid range.");
        }
        String total = header.substring(header.lastIndexOf('/') + 1);
        if (total.equals("*")) return -1;
        try { return Long.parseLong(total); }
        catch (NumberFormatException invalid) { throw new MusicCatalog.RequestException("Audio exceeds 32 MiB."); }
    }

    static void check(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled.");
        if (System.nanoTime() >= deadline) throw new MusicCatalog.RequestException("Audio transfer timed out.");
    }
}
