package family.blakey.uptimererer.core.models;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import family.blakey.uptimererer.checkererer.Request;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class WebsiteTest {

  private static Website of(String url) {
    return new Website(
        url,
        Website.DEFAULT_METHOD,
        Website.DEFAULT_TIMEOUT_SECONDS,
        Website.DEFAULT_EXPECTED_STATUS_CODES,
        Website.DEFAULT_FAILURES_BEFORE_DOWN);
  }

  @Nested
  class Validate {

    @ParameterizedTest
    @ValueSource(
        strings = {"http://example.com", "https://example.com", "https://example.com/path"})
    void acceptsHttpAndHttpsUrls(String url) {
      assertDoesNotThrow(() -> of(url).validate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com", "example.com", "mailto:foo@example.com"})
    void rejectsNonHttpSchemes(String url) {
      assertThrows(IllegalArgumentException.class, () -> of(url).validate());
    }

    @Test
    void rejectsMissingHost() {
      assertThrows(IllegalArgumentException.class, () -> of("http:///path").validate());
    }

    @Test
    void rejectsMalformedUrl() {
      assertThrows(IllegalArgumentException.class, () -> of("http://exa mple.com").validate());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsNullOrEmptyUrl(String url) {
      assertThrows(IllegalArgumentException.class, () -> of(url).validate());
    }
  }

  @Nested
  class StatusExpected {

    @Test
    void trueForCodeInList() {
      assertTrue(of("https://example.com").statusExpected(200));
      assertTrue(of("https://example.com").statusExpected(204));
    }

    @Test
    void falseForCodeNotInList() {
      assertFalse(of("https://example.com").statusExpected(404));
    }

    @Test
    void falseWhenExpectedCodesIsNull() {
      Website website = new Website("https://example.com", Website.DEFAULT_METHOD, 10, null, 3);

      assertFalse(website.statusExpected(200));
    }

    @Test
    void respectsCustomExpectedCodes() {
      Website website =
          new Website("https://example.com", Website.DEFAULT_METHOD, 10, List.of(418), 3);

      assertTrue(website.statusExpected(418));
      assertFalse(website.statusExpected(200));
    }
  }

  @Nested
  class From {

    @Test
    void appliesDefaultsAroundTheRequestedUrl() {
      Website website = Website.from(new Request("https://example.com"));

      assertEquals("https://example.com", website.url());
      assertEquals(Website.DEFAULT_METHOD, website.method());
      assertEquals(Website.DEFAULT_TIMEOUT_SECONDS, website.timeoutSeconds());
      assertEquals(Website.DEFAULT_EXPECTED_STATUS_CODES, website.expectedStatusCodes());
      assertEquals(Website.DEFAULT_FAILURES_BEFORE_DOWN, website.failuresBeforeDown());
    }
  }
}
