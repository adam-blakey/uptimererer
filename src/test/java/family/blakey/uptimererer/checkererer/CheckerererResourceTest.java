package family.blakey.uptimererer.checkererer;

import java.net.URL;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class CheckerererResourceTest {

    // The Lambda test support points RestAssured's defaults at the mock event
    // server, which only accepts POSTed events — resolve the real HTTP URL.
    @TestHTTPResource("/checkererer")
    URL checkererer;

    @Test
    void testCheckEndpoint() {
        given()
          .when().get(checkererer)
          .then()
             .statusCode(204);
    }

}
