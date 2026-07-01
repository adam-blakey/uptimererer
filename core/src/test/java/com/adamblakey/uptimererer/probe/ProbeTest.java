package com.adamblakey.uptimererer.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.adamblakey.uptimererer.events.SiteConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbeTest {

    private final Probe probe = new Probe();

    @Test
    void reportsUpForExpectedStatus() throws IOException {
        HttpServer server = start(respondWith(200));
        try {
            ProbeResult result = probe.check(site(url(server)));
            assertTrue(result.up());
            assertEquals(200, result.httpStatus());
            assertEquals("", result.error());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsDownForUnexpectedStatus() throws IOException {
        HttpServer server = start(respondWith(503));
        try {
            ProbeResult result = probe.check(site(url(server)));
            assertFalse(result.up());
            assertEquals(503, result.httpStatus());
            assertFalse(result.error().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void honoursCustomExpectedStatus() throws IOException {
        HttpServer server = start(respondWith(418));
        try {
            SiteConfig site = new SiteConfig("test", url(server), null, 0, List.of(418), 0).withDefaults();
            assertTrue(probe.check(site).up());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsDownWhenConnectionRefused() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } // socket closed: nothing is listening on the port now

        ProbeResult result = probe.check(site("http://127.0.0.1:" + port + "/"));
        assertFalse(result.up());
        assertEquals(0, result.httpStatus());
        assertFalse(result.error().isEmpty());
    }

    @Test
    void reportsDownOnTimeout() throws IOException {
        HttpServer server = start(exchange -> {
            sleepUninterruptibly();
            respond(exchange, 200);
        });
        try {
            SiteConfig site = new SiteConfig("test", url(server), null, 1, null, 0).withDefaults();
            ProbeResult result = probe.check(site);
            assertFalse(result.up());
            assertFalse(result.error().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void followsRedirects() throws IOException {
        HttpServer server = server();
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final");
            respond(exchange, 302);
        });
        server.createContext("/final", respondWith(200));
        server.start();
        try {
            assertTrue(probe.check(site(url(server))).up());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsDownForRedirectLoop() throws IOException {
        HttpServer server = server();
        AtomicInteger n = new AtomicInteger();
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop" + n.incrementAndGet());
            respond(exchange, 302);
        });
        server.start();
        try {
            // The JDK client stops after its redirect limit and returns the last 3xx,
            // which is not an expected status, so the site reads as down.
            assertFalse(probe.check(site(url(server))).up());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUntrustedCertificate(@TempDir Path tempDir) throws Exception {
        HttpsServer server = startHttps(tempDir);
        try {
            ProbeResult result = probe.check(site("https://127.0.0.1:" + server.getAddress().getPort() + "/"));
            assertFalse(result.up());
            assertEquals(0, result.httpStatus());
        } finally {
            server.stop(0);
        }
    }

    // --- helpers ---------------------------------------------------------

    private static SiteConfig site(String url) {
        return new SiteConfig("test", url, null, 0, null, 0).withDefaults();
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static HttpServer start(HttpHandler handler) throws IOException {
        HttpServer server = server();
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static HttpHandler respondWith(int status) {
        return exchange -> respond(exchange, status);
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void sleepUninterruptibly() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static HttpsServer startHttps(Path tempDir) throws Exception {
        Path keystore = tempDir.resolve("keystore.p12");
        generateSelfSignedKeystore(keystore);

        char[] password = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/", respondWith(200));
        server.start();
        return server;
    }

    private static void generateSelfSignedKeystore(Path keystore) throws IOException, InterruptedException {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=localhost", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed to generate a test keystore");
        }
    }
}
