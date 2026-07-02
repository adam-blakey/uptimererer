package family.blakey.uptimererer.checkererer;

import java.time.Instant;

import family.blakey.uptimererer.core.db.StateRecord;
import family.blakey.uptimererer.core.db.StateRepository;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/checkererer")
public class CheckerererResource {

    private final StateRepository repository;

    public CheckerererResource(StateRepository repository) {
        this.repository = repository;
    }

    @GET
    public void check() {
        repository.put(new StateRecord("example", Instant.now()));
    }
}
