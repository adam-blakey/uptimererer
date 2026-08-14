package family.blakey.uptimererer.core.models;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

public record Website(
        String id,
        String url,
        String method,
        int timeoutSeconds,
        List<Integer> expectedStatusCodes,
        int failuresBeforeDown) {

    public static final String DEFAULT_METHOD = "GET";
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;
    public static final List<Integer> DEFAULT_EXPECTED_STATUS_CODES = List.of(200, 204);
    public static final int DEFAULT_FAILURES_BEFORE_DOWN = 3;

    public void validate() throws IllegalArgumentException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("website config: missing id");
        }

        final URI uri;
        try {
            uri = new URI(url == null ? "" : url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("website '" + id + "': invalid url: " + e.getMessage(), e);
        }

        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                    "website '" + id + "': url scheme must be http or https, got " + scheme);
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("website '" + id + "': url has no host");
        }
    }

    public boolean statusExpected(int code) {
        return expectedStatusCodes != null && expectedStatusCodes.contains(code);
    }

}
