package family.blakey.uptimererer.checkererer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CheckerererHandlerTest {

  @Inject CheckerererHandler handler;

  @Test
  void handlesCheckRequest() {
    SQSBatchResponse response =
        handler.handleRequest(event("{\"url\": \"https://example.com\"}"), null);

    assertTrue(response.getBatchItemFailures().isEmpty());
  }

  @Test
  void reportsMalformedBodyPerMessage() {
    SQSBatchResponse response = handler.handleRequest(event("not json"), null);

    assertEquals(1, response.getBatchItemFailures().size());
    assertEquals("message-1", response.getBatchItemFailures().getFirst().getItemIdentifier());
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
