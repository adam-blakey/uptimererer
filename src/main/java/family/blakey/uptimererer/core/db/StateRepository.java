package family.blakey.uptimererer.core.db;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@ApplicationScoped
public class StateRepository {

    private final DynamoDbTable<StateRecord> table;

    public StateRepository(DynamoDbEnhancedClient db,
            @ConfigProperty(name = "uptimererer.state-table") String tableName) {
        this.table = db.table(tableName, StateRecord.TABLE_SCHEMA);
    }

    public void put(StateRecord record) {
        table.putItem(record);
    }
}
