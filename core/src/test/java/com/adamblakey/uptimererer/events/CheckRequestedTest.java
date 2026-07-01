package com.adamblakey.uptimererer.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.adamblakey.uptimererer.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CheckRequestedTest {

    private final ObjectMapper mapper = Json.mapper();

    @Test
    void parsesFlatDetailIntoSiteConfig() throws Exception {
        String detail = "{\"id\":\"example\",\"url\":\"https://example.com\",\"timeoutSeconds\":5,"
                + "\"requestedAt\":\"2026-07-01T12:00:00Z\"}";

        CheckRequested request = CheckRequested.fromDetail(mapper, mapper.readTree(detail));

        assertEquals("example", request.site().id());
        assertEquals("https://example.com", request.site().url());
        assertEquals(5, request.site().timeoutSeconds());
        assertEquals(Instant.parse("2026-07-01T12:00:00Z"), request.requestedAt());
    }

    @Test
    void roundTripsThroughFlatDetail() {
        SiteConfig site = new SiteConfig("example", "https://example.com", null, 5, null, 0);
        CheckRequested original = new CheckRequested(site, Instant.parse("2026-07-01T12:00:00Z"));

        JsonNode detail = original.toDetail(mapper);
        assertEquals("example", detail.get("id").asText());
        assertEquals("https://example.com", detail.get("url").asText());
        assertEquals("2026-07-01T12:00:00Z", detail.get("requestedAt").asText());

        CheckRequested parsed = CheckRequested.fromDetail(mapper, detail);
        assertEquals(site.id(), parsed.site().id());
        assertEquals(site.timeoutSeconds(), parsed.site().timeoutSeconds());
        assertEquals(original.requestedAt(), parsed.requestedAt());
    }
}
