package family.blakey.uptimererer.checkererer;

import java.net.URL;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class HealthHandlerTest {

    @TestHTTPResource("/health")
    URL health;

    @Test
    void testCheckEndpoint() {
        given()
          .when().get(health)
          .then()
             .statusCode(204);
    }

}
