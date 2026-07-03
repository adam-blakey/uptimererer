package family.blakey.uptimererer.checkererer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import family.blakey.uptimererer.core.db.StateRecord;
import family.blakey.uptimererer.core.db.StateRepository;
import family.blakey.uptimererer.core.db.Status;
import family.blakey.uptimererer.core.events.CheckRequest;
import family.blakey.uptimererer.core.events.CheckRequestedEvent;
import family.blakey.uptimererer.notifierer.StatusChangeNotifier;
import jakarta.inject.Named;
import java.time.Instant;
import java.util.Optional;

/**
 * Entry point when the checkererer runs as a Lambda targeted by the event-bus rule: each {@code
 * check-requested} event carries one {@link CheckRequest}. The website is pinged, the observed
 * state is written to DynamoDB, and a status flip is handed to the notifierer.
 *
 * <p>A website that fails its ping is a successfully processed event — only genuine processing
 * failures (malformed event, DynamoDB unavailable) throw, so EventBridge/Lambda retry them.
 */
@Named("checkererer")
public class CheckerererHandler implements RequestHandler<CheckRequestedEvent, Void> {

  private final HttpChecker checker;
  private final StateRepository repository;
  private final StatusChangeNotifier notifier;

  public CheckerererHandler(
      HttpChecker checker, StateRepository repository, StatusChangeNotifier notifier) {
    this.checker = checker;
    this.repository = repository;
    this.notifier = notifier;
  }

  @Override
  public Void handleRequest(CheckRequestedEvent event, Context context) {
    CheckRequest request = CheckRequest.Validator.validate(event == null ? null : event.detail());
    checkAndRecord(request);
    return null;
  }

  private void checkAndRecord(CheckRequest request) {
    Status observed = checker.check(request.url());
    Instant now = Instant.now();

    Optional<StateRecord> previous = repository.find(request.url());
    boolean changed = previous.isPresent() && previous.get().status() != observed;
    Instant lastChangedAt = changed || previous.isEmpty() ? now : previous.get().lastChangedAt();

    StateRecord current = new StateRecord(request.url(), now, observed, lastChangedAt);
    repository.put(current);

    if (changed) {
      notifier.statusChanged(current);
    }
  }
}
