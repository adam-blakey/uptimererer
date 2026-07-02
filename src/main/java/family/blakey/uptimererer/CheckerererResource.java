package family.blakey.uptimererer;

import java.time.Instant;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Path("/checkererer")
public class CheckerererResource {

    private final DynamoDbClient db;
    private final String table;

    public CheckerererResource(DynamoDbClient db,
            @ConfigProperty(name = "uptimererer.state-table") String table) {
        this.db = db;
        this.table = table;
    }

    @GET
    public void check() {
        db.putItem(r -> r.tableName(table).item(Map.of(
                "websiteId", AttributeValue.fromS("example"),
                "lastCheckedAt", AttributeValue.fromS(Instant.now().toString()))));
    }
}
