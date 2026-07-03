package family.blakey.uptimererer.checkererer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import family.blakey.uptimererer.core.db.StateRecord;
import family.blakey.uptimererer.core.db.StateRepository;
import family.blakey.uptimererer.core.db.Status;
import family.blakey.uptimererer.core.events.CheckRequest;
import family.blakey.uptimererer.core.events.CheckRequestedEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CheckerererHandlerTest {

  @Inject CheckerererHandler handler;

  @Inject StateRepository repository;

  private HttpServer server;
  private final AtomicInteger statusCode = new AtomicInteger(200);

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(statusCode.get(), -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void recordsUpWebsite() {
    handler.handleRequest(event(url()), null);

    StateRecord record = repository.find(url()).orElseThrow();
    assertEquals(Status.UP, record.status());
    assertEquals(record.lastCheckedAt(), record.lastChangedAt());
  }

  @Test
  void recordsStatusFlipAndKeepsLastChangedAtOtherwise() {
    handler.handleRequest(event(url()), null);
    StateRecord up = repository.find(url()).orElseThrow();

    // still up: lastCheckedAt moves on, lastChangedAt doesn't
    handler.handleRequest(event(url()), null);
    StateRecord stillUp = repository.find(url()).orElseThrow();
    assertEquals(Status.UP, stillUp.status());
    assertEquals(up.lastChangedAt(), stillUp.lastChangedAt());
    assertTrue(!stillUp.lastCheckedAt().isBefore(up.lastCheckedAt()));

    // down: the flip is recorded
    statusCode.set(503);
    handler.handleRequest(event(url()), null);
    StateRecord down = repository.find(url()).orElseThrow();
    assertEquals(Status.DOWN, down.status());
    assertTrue(down.lastChangedAt().isAfter(up.lastChangedAt()));
  }

  @Test
  void rejectsEventWithoutUrl() {
    assertThrows(
        IllegalArgumentException.class,
        () -> handler.handleRequest(CheckRequestedEvent.of(new CheckRequest(null)), null));
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  private static CheckRequestedEvent event(String url) {
    return CheckRequestedEvent.of(new CheckRequest(url));
  }
}
