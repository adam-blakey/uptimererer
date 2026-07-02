package family.blakey.uptimererer;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class CheckerererResourceTest {
    @Test
    void testCheckEndpoint() {
        given()
          .when().get("/checkererer")
          .then()
             .statusCode(204);
    }

}