package family.blakey.uptimererer.core.models;

import static family.blakey.uptimererer.core.Helpers.describe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

public final class Poll {
  public static Result check(Website website) {
    Duration timeout = Duration.ofSeconds(website.timeoutSeconds());

    final HttpRequest request;
    try {
      request =
          HttpRequest.newBuilder()
              .uri(URI.create(website.url()))
              .timeout(timeout)
              .method(website.method(), HttpRequest.BodyPublishers.noBody())
              .build();
    } catch (RuntimeException e) {
      return new Result(false, 0, Duration.ZERO, "build request: " + describe(e));
    }

    Instant start = Instant.now();
    try (HttpClient client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(timeout)
            .build()) {

      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      Duration latency = Duration.between(start, Instant.now());

      String message = "";
      int status = response.statusCode();
      boolean statusExpected = website.statusExpected(status);
      if (!website.statusExpected(status)) {
        message = "unexpected status " + status;
      }

      return new Result(statusExpected, status, latency, message);
    } catch (IOException e) {
      return new Result(false, 0, Duration.between(start, Instant.now()), describe(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(false, 0, Duration.between(start, Instant.now()), describe(e));
    }
  }

  public record Result(boolean statusExpected, int httpStatus, Duration latency, String error) {}
}
