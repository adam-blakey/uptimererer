package family.blakey.uptimererer.checkererer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import family.blakey.uptimererer.core.db.Status;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpCheckerTest {

  private final HttpChecker checker = new HttpChecker(Duration.ofSeconds(2));

  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void okResponseIsUp() {
    respondWith(200);

    assertEquals(Status.UP, checker.check(url()));
  }

  @Test
  void serverErrorIsDown() {
    respondWith(503);

    assertEquals(Status.DOWN, checker.check(url()));
  }

  @Test
  void unreachableServerIsDown() {
    String unreachable = url();
    server.stop(0);

    assertEquals(Status.DOWN, checker.check(unreachable));
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  private void respondWith(int statusCode) {
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(statusCode, -1);
          exchange.close();
        });
  }
}
