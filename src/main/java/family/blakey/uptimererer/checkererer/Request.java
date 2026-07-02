package family.blakey.uptimererer.checkererer;

import org.hibernate.validator.constraints.URL;

public record Request(@URL String url) {
}
