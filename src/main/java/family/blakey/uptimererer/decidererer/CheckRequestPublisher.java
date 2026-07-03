package family.blakey.uptimererer.decidererer;

import family.blakey.uptimererer.core.events.CheckRequest;
import java.util.List;

/** Where the decidererer's pending requests go — in production, the EventBridge bus. */
public interface CheckRequestPublisher {

  void publish(List<CheckRequest> requests);
}
