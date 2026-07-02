package family.blakey.uptimererer.checkererer;

import java.util.List;

import io.netty.util.internal.SuppressJava6Requirement;
import org.junit.jupiter.api.Test;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RequestTest {

    @ParameterizedTest
    @ValueSource(strings = {"www.google.com", "google.com.", "1.1.1.1", "127.0.0.1", "facebook.com", "com.com", "example.com/path", "example.com:123", "example.com:123/path"})
    void acceptsValidUrl(String url) {
        var request = new Request(url);

        assertNotNull(request);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"google.c", "1", "1.1", "1.1.1.1.", "255.255.255.256", "breakfast", "path/example.com"})
    void rejectsInvalidUrl(String url) {
        var request = new Request(url);

        assertNotNull(request);
    }
}
