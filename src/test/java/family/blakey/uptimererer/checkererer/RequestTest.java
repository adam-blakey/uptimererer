package family.blakey.uptimererer.checkererer;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RequestTest {

    @Nested
    abstract class WithScheme {
        protected String scheme;

        @ParameterizedTest
        @ValueSource(strings = {"www.google.com", "google.com.", "localhost", "1", "1.", "1.1.1.1", "127.0.0.1", "facebook.com", "com.com", "example.com/path", "example.com:123", "example.com:123/path", "example/path.com"})
        void acceptsValidUrl(String url) {
            var request = new Request(scheme + url);

            var parsed = Request.Validator.validate(request);

            assertNotNull(parsed);
        }

        @ParameterizedTest
        @EmptySource
        @ValueSource(strings = {"1.1", "1.1.1.1.", "255.255.255.256"})
        void rejectsInvalidUrl(String url) {
            var request = new Request(scheme + url);

            assertThrows(IllegalArgumentException.class, () -> Request.Validator.validate(request));
        }
    }

    @Nested
    @SuppressWarnings("HttpUrlsUsage")
    class Http extends WithScheme {
        public Http() {
            scheme = "http://";
        }
    }

    @Nested
    class Https extends WithScheme {
        public Https() {
            scheme = "https://";
        }
    }

    @Nested
    class Schemeless {
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"google.com", "1.1.1.1"})
        void rejectsInvalidUrl(String url) {
            var request = new Request(url);

            assertThrows(IllegalArgumentException.class, () -> Request.Validator.validate(request));
        }
    }
}
