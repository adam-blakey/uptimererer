package family.blakey.uptimererer.core.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * A request to check one website, {@code {"url": ...}}. This is the contract shared across the
 * pipeline: the body of a direct SQS check message, the value of a Systems Manager URL config, and
 * the {@code detail} of a {@link CheckRequestedEvent} on the event bus.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckRequest(String url) {
  public static class Validator {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public static CheckRequest validate(CheckRequest request) throws IllegalArgumentException {
      if (request == null || request.url() == null || request.url().isBlank()) {
        throw new IllegalArgumentException("request has no url");
      }

      URI uri;
      try {
        uri = URI.create(request.url()).parseServerAuthority();
      } catch (URISyntaxException | IllegalArgumentException e) {
        throw new IllegalArgumentException(e.getMessage());
      }

      if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme())) {
        throw new IllegalArgumentException(
            "scheme must be one of: " + String.join(", ", ALLOWED_SCHEMES));
      }

      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw new IllegalArgumentException("domain is required");
      }

      return request;
    }
  }
}
