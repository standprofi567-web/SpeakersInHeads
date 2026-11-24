package dev.klxsh.speakersinheads.music;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** One HTTP exchange over a checked IP. HTTPS stays end-to-end between Java and the original host. */
final class PinnedProxy implements AutoCloseable {
    private static final int TIMEOUT = 6000;
    private final ServerSocket listener;
    private final Socket upstream = new Socket();
    private final URI target;
    private final InetAddress address;
    private final boolean tls;
    private final Thread worker;
    private volatile Socket downstream;

    PinnedProxy(URI target, InetAddress address) throws IOException {
        this.target = target;
        this.address = address;
        this.tls = target.getScheme().equalsIgnoreCase("https");
        listener = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        listener.setSoTimeout(TIMEOUT);
        worker = Thread.ofVirtual().name("sih-pinned-http").start(this::serve);
    }

    InetSocketAddress endpoint() {
        return new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort());
    }

    private void serve() {
        try (Socket client = listener.accept(); upstream) {
            downstream = client;
            client.setSoTimeout(TIMEOUT);
            upstream.setSoTimeout(TIMEOUT);
            InputStream request = new BufferedInputStream(client.getInputStream());
            OutputStream response = client.getOutputStream();
            String first = line(request);
            int bytes = first.length();
            for (String header; !(header = line(request)).isEmpty();) {
                bytes += header.length();
                if (bytes > 16384) throw new IOException("Request headers too large.");
            }
            int port = target.getPort() >= 0 ? target.getPort() : tls ? 443 : 80;
            String host = target.getHost();
            String path = target.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
            String[] requestLine = first.split(" ", 3);
            if (requestLine.length != 3 || !(tls ? requestLine[0].equals("CONNECT")
                    : requestLine[0].equals("GET") || requestLine[0].equals("HEAD"))) return;
            if (tls) {
                if (!requestLine[1].equalsIgnoreCase(host + ":" + port)) return;
            } else if (!URI.create(requestLine[1]).equals(target)) {
                return;
            }

            upstream.connect(new InetSocketAddress(address, port), TIMEOUT);
            if (tls) {
                write(response, "HTTP/1.1 200 Connection established\r\n\r\n");
                Thread pipe = Thread.ofVirtual().name("sih-pinned-upload").start(() -> {
                    try {
                        request.transferTo(upstream.getOutputStream());
                        upstream.shutdownOutput();
                    } catch (IOException failure) {
                        closeQuietly(upstream);
                    }
                });
                try {
                    upstream.getInputStream().transferTo(response);
                } finally {
                    pipe.interrupt();
                }
            } else {
                write(upstream.getOutputStream(), requestLine[0] + " " + path + " HTTP/1.1\r\nHost: " + target.getRawAuthority()
                        + "\r\nUser-Agent: SpeakersInHeads/0.7.3\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n");
                upstream.getInputStream().transferTo(response);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Connection errors surface on the request owner. Never log the original URL.
        } finally {
            closeQuietly(listener);
        }
    }

    private static String line(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1 && bytes.size() < 8192;) {
            if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII).stripTrailing();
            bytes.write(value);
        }
        throw new IOException("Invalid request header.");
    }

    private static void write(OutputStream stream, String text) throws IOException {
        stream.write(text.getBytes(StandardCharsets.US_ASCII));
        stream.flush();
    }

    private static void closeQuietly(AutoCloseable socket) {
        if (socket == null) return;
        try { socket.close(); } catch (Exception ignored) {}
    }

    @Override public void close() {
        closeQuietly(upstream);
        closeQuietly(listener);
        closeQuietly(downstream);
        worker.interrupt();
    }
}
