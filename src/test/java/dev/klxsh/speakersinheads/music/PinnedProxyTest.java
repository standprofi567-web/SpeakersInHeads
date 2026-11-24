package dev.klxsh.speakersinheads.music;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PinnedProxyTest {
    @Test
    void connectTunnelPassesBytesWithoutInterpretingTheEncryptedPayload() throws Exception {
        InetAddress local = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (var origin = new ServerSocket(0, 1, local)) {
            origin.setSoTimeout(5000);
            Thread.ofVirtual().start(() -> {
                try (var socket = origin.accept()) {
                    socket.setSoTimeout(5000);
                    byte[] bytes = socket.getInputStream().readNBytes(3);
                    socket.getOutputStream().write(bytes);
                } catch (java.io.IOException ignored) {
                    // An unread response will fail the assertion on the client.
                }
            });
            URI target = URI.create("https://verified.invalid:" + origin.getLocalPort() + "/audio");
            try (var proxy = new PinnedProxy(target, local); var client = new Socket()) {
                client.connect(proxy.endpoint(), 5000);
                client.setSoTimeout(5000);
                client.getOutputStream().write(("CONNECT " + target.getRawAuthority() + " HTTP/1.1\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                byte[] established = "HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                org.junit.jupiter.api.Assertions.assertArrayEquals(established, client.getInputStream().readNBytes(established.length));
                byte[] opaque = new byte[]{22, 3, 1};
                client.getOutputStream().write(opaque);
                org.junit.jupiter.api.Assertions.assertArrayEquals(opaque, client.getInputStream().readNBytes(opaque.length));
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"GET", "HEAD"})
    void httpUsesPinnedAddressWithoutResolvingTheOriginalHostAgain(String method) throws Exception {
        InetAddress local = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (var origin = new ServerSocket(0, 1, local)) {
            origin.setSoTimeout(7000);
            var captured = new FutureTask<>(() -> {
                try (var socket = origin.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                            StandardCharsets.US_ASCII));
                    String request = input.readLine();
                    String host = input.readLine();
                    String body = method.equals("GET") ? "OK" : "";
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n" + body)
                            .getBytes(StandardCharsets.US_ASCII));
                    return request + "|" + host;
                }
            });
            Thread.ofVirtual().start(captured);
            URI url = URI.create("http://audio.invalid:" + origin.getLocalPort() + "/song.wav?quality=original");
            try (var proxy = new PinnedProxy(url, local);
                 var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                         .proxy(ProxySelector.of(proxy.endpoint())).build()) {
                var response = client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(7))
                                .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals(method.equals("GET") ? "OK" : "", response.body());
            }
            assertEquals(method + " /song.wav?quality=original HTTP/1.1|Host: audio.invalid:" + origin.getLocalPort(),
                    captured.get(7, TimeUnit.SECONDS));
        }
    }
}
