package family.blakey.uptimererer.checkererer;

import jakarta.ws.rs.BadRequestException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

public record Request(String url) {
    public static class Validator {
        private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

        public static Request validate(Request request) throws BadRequestException {
            if (request == null || request.url() == null || request.url().isBlank()) {
                throw new BadRequestException("request has no url");
            }

            URI uri;
            try {
               uri = URI.create(request.url()).parseServerAuthority();
            } catch (URISyntaxException | IllegalArgumentException e) {
                throw new BadRequestException(e.getMessage());
            }

            if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme())) {
                throw new BadRequestException("scheme must be one of: " + String.join(", ", ALLOWED_SCHEMES));
            }

            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BadRequestException("domain is required");
            }

            return request;
        }
    }
}
