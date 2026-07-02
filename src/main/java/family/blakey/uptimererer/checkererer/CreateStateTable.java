package family.blakey.uptimererer.checkererer;

import family.blakey.uptimererer.core.db.StateRecord;
import io.quarkus.arc.profile.IfBuildProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Creates the state table at startup, because the local emulator starts empty.
 * Only enabled in dev/test; in production the table is provisioned out-of-band.
 */
@IfBuildProfile(anyOf = { "dev", "test" })
@ApplicationScoped
public class CreateStateTable {

    @Inject
    DynamoDbClient db;

    @ConfigProperty(name = "uptimererer.state-table")
    String table;

    @ConfigProperty(name = "uptimererer.create-table-on-start", defaultValue = "false")
    boolean createTableOnStart;

    @SuppressWarnings("unused")
    void onStart(@Observes StartupEvent event) {
        if (!createTableOnStart) {
            return;
        }
        try {
            db.createTable(getCreateTableRequest());
            db.waiter().waitUntilTableExists(r -> r.tableName(table));
        } catch (ResourceInUseException alreadyExists) {
            // the table survives from an earlier run of this emulator
        }
    }

    private CreateTableRequest getCreateTableRequest() {
        var metadata = StateRecord.TABLE_SCHEMA.tableMetadata();
        var partitionKey = metadata.primaryPartitionKey();

        var attributeDefinitionsBuilder = AttributeDefinition.builder()
                .attributeName(partitionKey)
                .attributeType(metadata.scalarAttributeType(partitionKey).orElseThrow());

        var keySchemaBuilder = KeySchemaElement.builder()
                .attributeName(partitionKey)
                .keyType(KeyType.HASH);

        var builder = CreateTableRequest.builder()
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attributeDefinitionsBuilder.build())
                .keySchema(keySchemaBuilder.build());

        return builder.build();
    }
}
