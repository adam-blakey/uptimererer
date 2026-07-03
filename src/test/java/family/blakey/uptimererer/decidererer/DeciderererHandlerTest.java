package family.blakey.uptimererer.decidererer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.blakey.uptimererer.core.events.CheckRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeciderererHandlerTest {

  private final List<CheckRequest> configured = new ArrayList<>();
  private final List<CheckRequest> published = new ArrayList<>();

  private final DeciderererHandler handler =
      new DeciderererHandler(new ObjectMapper(), () -> List.copyOf(configured), published::addAll);

  @Test
  void tickFansOutToEveryConfiguredUrl() {
    configured.add(new CheckRequest("https://one.example.com"));
    configured.add(new CheckRequest("https://two.example.com"));

    // what the EventBridge scheduler actually delivers: JSON without a url
    SQSBatchResponse response =
        handler.handleRequest(event("{\"detail-type\": \"Scheduled Event\"}"), null);

    assertTrue(response.getBatchItemFailures().isEmpty());
    assertEquals(configured, published);
  }

  @Test
  void tickWithNothingConfiguredPublishesNothing() {
    SQSBatchResponse response = handler.handleRequest(event("{}"), null);

    assertTrue(response.getBatchItemFailures().isEmpty());
    assertTrue(published.isEmpty());
  }

  @Test
  void directCheckMessageIsForwardedAsIs() {
    configured.add(new CheckRequest("https://configured.example.com"));

    SQSBatchResponse response =
        handler.handleRequest(event("{\"url\": \"https://example.com\"}"), null);

    assertTrue(response.getBatchItemFailures().isEmpty());
    assertEquals(List.of(new CheckRequest("https://example.com")), published);
  }

  @Test
  void reportsMalformedBodyPerMessage() {
    SQSBatchResponse response = handler.handleRequest(event("not json"), null);

    assertEquals(1, response.getBatchItemFailures().size());
    assertEquals("message-1", response.getBatchItemFailures().getFirst().getItemIdentifier());
    assertTrue(published.isEmpty());
  }

  @Test
  void reportsInvalidUrlPerMessage() {
    SQSBatchResponse response = handler.handleRequest(event("{\"url\": \"not a url\"}"), null);

    assertEquals(1, response.getBatchItemFailures().size());
    assertTrue(published.isEmpty());
  }

  private static SQSEvent event(String body) {
    SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
    message.setMessageId("message-1");
    message.setBody(body);
    SQSEvent event = new SQSEvent();
    event.setRecords(List.of(message));
    return event;
  }
}
