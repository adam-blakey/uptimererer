package family.blakey.uptimererer.checkererer;

import family.blakey.uptimererer.core.db.StateRepository;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.Path;

@Path("/health")
public class HealthHandler {

  private final StateRepository repository;

  public HealthHandler(StateRepository repository) {
    this.repository = repository;
  }

  @GET
  public void check() {
    if (repository == null) {
      throw new InternalServerErrorException();
    }
  }
}
