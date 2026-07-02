package family.blakey.uptimererer.checkererer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

public record Request(String url) {
  public static class Validator {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public static Request validate(Request request) throws IllegalArgumentException {
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
