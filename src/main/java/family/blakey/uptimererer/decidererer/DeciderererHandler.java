package family.blakey.uptimererer.decidererer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.blakey.uptimererer.core.events.CheckRequest;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point when the decidererer runs as a Lambda behind the SQS event source mapping. It decides
 * which requests to make and publishes them to the event bus:
 *
 * <ul>
 *   <li>a scheduler tick (any JSON body without a {@code url}, e.g. the EventBridge scheduled
 *       event) fans out to every website configured in the {@link UrlConfigStore};
 *   <li>a direct check message ({@code {"url": ...}}, e.g. from {@code make send}) is validated and
 *       forwarded as a single pending request.
 * </ul>
 *
 * <p>Only genuine processing failures (non-JSON body, invalid url, Systems Manager or EventBridge
 * unavailable) are reported back to SQS, per message, for retry and eventual dead-lettering.
 */
@Named("decidererer")
public class DeciderererHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

  private final ObjectMapper json;
  private final UrlConfigStore urls;
  private final CheckRequestPublisher publisher;

  public DeciderererHandler(
      ObjectMapper json, UrlConfigStore urls, CheckRequestPublisher publisher) {
    this.json = json;
    this.urls = urls;
    this.publisher = publisher;
  }

  @Override
  public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
    List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
    for (SQSEvent.SQSMessage message : event.getRecords()) {
      try {
        decide(message.getBody());
      } catch (Exception processingFailure) {
        failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
      }
    }
    return new SQSBatchResponse(failures);
  }

  private void decide(String body) throws IOException {
    CheckRequest direct = json.readValue(body, CheckRequest.class);
    if (direct != null && direct.url() != null) {
      publisher.publish(List.of(CheckRequest.Validator.validate(direct)));
      return;
    }

    List<CheckRequest> pending = urls.urls();
    if (!pending.isEmpty()) {
      publisher.publish(pending);
    }
  }
}
