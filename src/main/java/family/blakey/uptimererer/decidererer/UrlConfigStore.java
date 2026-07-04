package family.blakey.uptimererer.decidererer;

import family.blakey.uptimererer.core.events.CheckRequest;
import java.util.List;

/** The set of websites the decidererer should have checked on every scheduler tick. */
public interface UrlConfigStore {

  List<CheckRequest> urls();
}
