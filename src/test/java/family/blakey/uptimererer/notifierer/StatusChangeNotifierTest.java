package family.blakey.uptimererer.notifierer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import family.blakey.uptimererer.core.db.StateRecord;
import family.blakey.uptimererer.core.db.Status;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StatusChangeNotifierTest {

  @Test
  void withoutATopicConfiguredOnlyLogs() {
    // no SnsClient must be touched when no topic is configured
    var notifier = new StatusChangeNotifier(null, Optional.empty());

    assertDoesNotThrow(
        () ->
            notifier.statusChanged(
                new StateRecord("https://example.com", Instant.now(), Status.DOWN, Instant.now())));
  }
}
