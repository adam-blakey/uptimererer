package family.blakey.uptimererer.checkererer;

import static io.restassured.RestAssured.given;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HealthHandlerTest {

  @TestHTTPResource("/health")
  URL health;

  @Test
  void testCheckEndpoint() {
    given().when().get(health).then().statusCode(204);
  }
}
