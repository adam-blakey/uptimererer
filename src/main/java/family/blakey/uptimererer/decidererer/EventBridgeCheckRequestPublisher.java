package family.blakey.uptimererer.decidererer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.blakey.uptimererer.core.events.CheckRequest;
import family.blakey.uptimererer.core.events.CheckRequestedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

/**
 * Publishes pending requests to the custom event bus as {@code check-requested} events, from where
 * the bus rule fans them out to the checkererer Lambda. PutEvents accepts at most ten entries per
 * call, so larger batches are chunked; any rejected entry fails the whole publish so SQS redelivers
 * the originating message.
 */
@ApplicationScoped
public class EventBridgeCheckRequestPublisher implements CheckRequestPublisher {

  private static final int MAX_ENTRIES_PER_PUT = 10;

  private final EventBridgeClient events;
  private final ObjectMapper json;
  private final String busName;

  public EventBridgeCheckRequestPublisher(
      EventBridgeClient events,
      ObjectMapper json,
      @ConfigProperty(name = "uptimererer.event-bus") String busName) {
    this.events = events;
    this.json = json;
    this.busName = busName;
  }

  @Override
  public void publish(List<CheckRequest> requests) {
    for (int from = 0; from < requests.size(); from += MAX_ENTRIES_PER_PUT) {
      List<CheckRequest> chunk =
          requests.subList(from, Math.min(from + MAX_ENTRIES_PER_PUT, requests.size()));
      put(chunk);
    }
  }

  private void put(List<CheckRequest> chunk) {
    List<PutEventsRequestEntry> entries = new ArrayList<>();
    for (CheckRequest request : chunk) {
      entries.add(
          PutEventsRequestEntry.builder()
              .eventBusName(busName)
              .source(CheckRequestedEvent.SOURCE)
              .detailType(CheckRequestedEvent.DETAIL_TYPE)
              .detail(toJson(request))
              .build());
    }

    PutEventsResponse response =
        events.putEvents(PutEventsRequest.builder().entries(entries).build());
    if (response.failedEntryCount() != null && response.failedEntryCount() > 0) {
      throw new IllegalStateException(
          response.failedEntryCount() + " of " + entries.size() + " events were rejected");
    }
  }

  private String toJson(CheckRequest request) {
    try {
      return json.writeValueAsString(request);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("could not serialise " + request, impossible);
    }
  }
}
