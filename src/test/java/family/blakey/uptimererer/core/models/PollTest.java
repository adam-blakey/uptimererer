package family.blakey.uptimererer.core.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PollTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String start(int status) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return "http://localhost:" + server.getAddress().getPort();
  }

  private static Website website(String url, List<Integer> expectedStatusCodes) {
    return new Website(url, Website.DEFAULT_METHOD, 5, expectedStatusCodes, 3);
  }

  @Test
  void reportsExpectedStatusAsSuccess() throws IOException {
    String url = start(200);

    Poll.Result result = Poll.check(website(url, List.of(200)));

    assertTrue(result.statusExpected());
    assertEquals(200, result.httpStatus());
    assertEquals("", result.error());
    assertFalse(result.latency().isNegative());
  }

  @Test
  void reportsUnexpectedStatusAsFailureWithMessage() throws IOException {
    String url = start(500);

    Poll.Result result = Poll.check(website(url, List.of(200)));

    assertFalse(result.statusExpected());
    assertEquals(500, result.httpStatus());
    assertEquals("unexpected status 500", result.error());
  }

  @Test
  void reportsConnectionFailureAsError() throws IOException {
    int deadPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    }

    Poll.Result result = Poll.check(website("http://localhost:" + deadPort, List.of(200)));

    assertFalse(result.statusExpected());
    assertEquals(0, result.httpStatus());
    assertFalse(result.error().isBlank());
  }

  @Test
  void reportsMalformedUrlAsError() {
    Poll.Result result = Poll.check(website("not a url", List.of(200)));

    assertFalse(result.statusExpected());
    assertEquals(0, result.httpStatus());
    assertEquals(Duration.ZERO, result.latency());
    assertFalse(result.error().isBlank());
  }
}
