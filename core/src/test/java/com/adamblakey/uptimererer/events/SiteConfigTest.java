package com.adamblakey.uptimererer.events;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SiteConfigTest {

    private static SiteConfig bare(String id, String url) {
        return new SiteConfig(id, url, null, 0, null, 0);
    }

    @Test
    void withDefaultsFillsOptionalFields() {
        SiteConfig s = bare("example", "https://example.com").withDefaults();

        assertEquals("GET", s.method());
        assertEquals(10, s.timeoutSeconds());
        assertEquals(3, s.failuresBeforeDown());
        assertTrue(s.statusExpected(200));
        assertTrue(s.statusExpected(204));
        assertFalse(s.statusExpected(301));
    }

    @Test
    void withDefaultsKeepsExplicitValues() {
        SiteConfig s = new SiteConfig("example", "https://example.com", "HEAD", 3, List.of(418), 1)
                .withDefaults();

        assertEquals("HEAD", s.method());
        assertEquals(3, s.timeoutSeconds());
        assertEquals(1, s.failuresBeforeDown());
        assertTrue(s.statusExpected(418));
        assertFalse(s.statusExpected(200));
    }

    @Test
    void validateAcceptsHttpAndHttps() {
        assertDoesNotThrow(() -> bare("a", "https://example.com/health").validate());
        assertDoesNotThrow(() -> bare("a", "http://example.com").validate());
    }

    @Test
    void validateRejectsMissingId() {
        assertThrows(IllegalArgumentException.class, () -> bare(null, "https://example.com").validate());
    }

    @Test
    void validateRejectsMissingUrl() {
        assertThrows(IllegalArgumentException.class, () -> bare("a", null).validate());
    }

    @Test
    void validateRejectsBadScheme() {
        assertThrows(IllegalArgumentException.class, () -> bare("a", "ftp://example.com").validate());
    }

    @Test
    void validateRejectsMissingHost() {
        assertThrows(IllegalArgumentException.class, () -> bare("a", "https://").validate());
    }
}
