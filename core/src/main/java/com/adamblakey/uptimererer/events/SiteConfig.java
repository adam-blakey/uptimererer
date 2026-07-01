package com.adamblakey.uptimererer.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Describes one site to monitor. It is stored in SSM under
 * {@code /uptimererer/sites} and embedded verbatim in every CheckRequested
 * event so the checker never has to read SSM itself.
 *
 * <p>The optional fields use zero/null as "unset" and are filled in by
 * {@link #withDefaults()}; unknown JSON properties (such as the event's
 * {@code requestedAt}) are ignored so the flat wire form parses straight into
 * this type.
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SiteConfig(
        String id,
        String url,
        String method,
        int timeoutSeconds,
        List<Integer> expectedStatusCodes,
        int failuresBeforeDown) {

    public static final String DEFAULT_METHOD = "GET";
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_FAILURES_BEFORE_DOWN = 3;
    public static final List<Integer> DEFAULT_EXPECTED_STATUS_CODES = List.of(200, 204);

    /** Returns a copy with the optional fields filled in from the defaults. */
    public SiteConfig withDefaults() {
        return new SiteConfig(
                id,
                url,
                (method == null || method.isBlank()) ? DEFAULT_METHOD : method,
                timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS,
                (expectedStatusCodes == null || expectedStatusCodes.isEmpty())
                        ? DEFAULT_EXPECTED_STATUS_CODES
                        : List.copyOf(expectedStatusCodes),
                failuresBeforeDown > 0 ? failuresBeforeDown : DEFAULT_FAILURES_BEFORE_DOWN);
    }

    /**
     * Validates that this config can drive a check.
     *
     * @throws IllegalArgumentException if the id or URL is missing or malformed
     */
    public void validate() {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("site config: missing id");
        }
        final URI uri;
        try {
            uri = new URI(url == null ? "" : url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("site '" + id + "': invalid url: " + e.getMessage(), e);
        }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                    "site '" + id + "': url scheme must be http or https, got " + scheme);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("site '" + id + "': url has no host");
        }
    }

    /** Reports whether {@code code} counts as a successful response. */
    public boolean statusExpected(int code) {
        return expectedStatusCodes != null && expectedStatusCodes.contains(code);
    }
}
