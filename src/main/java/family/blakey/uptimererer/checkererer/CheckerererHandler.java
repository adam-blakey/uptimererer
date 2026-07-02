package family.blakey.uptimererer.checkererer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import family.blakey.uptimererer.core.db.StateRecord;
import family.blakey.uptimererer.core.db.StateRepository;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point when the checkererer runs as a Lambda behind the SQS event source mapping. Each
 * message body is a {@link Request}.
 *
 * <p>A website that fails its ping is a successfully processed message — only genuine processing
 * failures (malformed body, DynamoDB unavailable) are reported back to SQS, per message, for retry
 * and eventual dead-lettering.
 */
public class CheckerererHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

  @Inject ObjectMapper json;

  @Inject StateRepository repository;

  @Override
  public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
    List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
    for (SQSEvent.SQSMessage message : event.getRecords()) {
      try {
        Request request = parse(message.getBody());
        checkAndRecord(request);
      } catch (Exception processingFailure) {
        failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
      }
    }
    return new SQSBatchResponse(failures);
  }

  private Request parse(String body) throws java.io.IOException {
    Request request = json.readValue(body, Request.class);
    return Request.Validator.validate(request);
  }

  private void checkAndRecord(Request request) {
    // TODO: actually make the request.
    repository.put(new StateRecord(request.url(), Instant.now()));
  }
}
