package family.blakey.uptimererer.core.db;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@ApplicationScoped
public class StateRepository {

  private final DynamoDbTable<StateRecord> table;

  public StateRepository(
      DynamoDbEnhancedClient db,
      @ConfigProperty(name = "uptimererer.state-table") String tableName) {
    this.table = db.table(tableName, StateRecord.TABLE_SCHEMA);
  }

  public void put(StateRecord record) {
    table.putItem(record);
  }

  public Optional<StateRecord> find(String websiteId) {
    return Optional.ofNullable(table.getItem(Key.builder().partitionValue(websiteId).build()));
  }
}
