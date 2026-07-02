package family.blakey.uptimererer;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/checkererer")
public class CheckerererResource {

    @GET
    public void check() {
        return;
    }
}
