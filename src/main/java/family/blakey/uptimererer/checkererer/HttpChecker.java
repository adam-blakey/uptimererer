package family.blakey.uptimererer.checkererer;

import family.blakey.uptimererer.core.db.Status;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Makes the actual network request: a website is {@link Status#UP} when a GET (after following
 * redirects) answers with a non-error status within the timeout, and {@link Status#DOWN} on an
 * error status, a timeout, or any connection failure.
 */
@ApplicationScoped
public class HttpChecker {

  private final HttpClient client;
  private final Duration timeout;

  public HttpChecker(
      @ConfigProperty(name = "uptimererer.check-timeout", defaultValue = "10s") Duration timeout) {
    this.timeout = timeout;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  public Status check(String url) {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
    try {
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() < 400 ? Status.UP : Status.DOWN;
    } catch (IOException unreachable) {
      return Status.DOWN;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Status.DOWN;
    }
  }
}
