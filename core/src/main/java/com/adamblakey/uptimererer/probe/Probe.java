package com.adamblakey.uptimererer.probe;

import com.adamblakey.uptimererer.events.SiteConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Performs a single HTTP(S) uptime check against a site.
 *
 * <p>The result is {@code up} iff the request completes and the response
 * status is one of the site's expected codes. TLS certificates are validated
 * (the JDK client's default) and redirects are followed up to the JDK's limit
 * ({@code jdk.httpclient.redirects.retrylimit}, default 5); a redirect that
 * never resolves therefore surfaces as a non-expected status rather than a
 * hang.
 */
public final class Probe {

    /** Runs one request against the site using its configured method and timeout. */
    public ProbeResult check(SiteConfig site) {
        Duration timeout = Duration.ofSeconds(site.timeoutSeconds());

        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(site.url()))
                    .timeout(timeout)
                    .method(site.method(), HttpRequest.BodyPublishers.noBody())
                    .build();
        } catch (RuntimeException e) {
            return new ProbeResult(false, 0, Duration.ZERO, "build request: " + describe(e));
        }

        Instant start = Instant.now();
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build()) {

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            Duration latency = Duration.between(start, Instant.now());
            int status = response.statusCode();
            if (site.statusExpected(status)) {
                return new ProbeResult(true, status, latency, "");
            }
            return new ProbeResult(false, status, latency, "unexpected status " + status);
        } catch (IOException e) {
            return new ProbeResult(false, 0, Duration.between(start, Instant.now()), describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, 0, Duration.between(start, Instant.now()), describe(e));
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
