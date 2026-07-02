package family.blakey.uptimererer.core.db;

import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;

@DynamoDbImmutable(builder = StateRecord.Builder.class)
public record StateRecord(@DynamoDbPartitionKey String websiteId, Instant lastCheckedAt) {

  public static final TableSchema<StateRecord> TABLE_SCHEMA =
      TableSchema.fromClass(StateRecord.class);

  /** The request that creates the table this record lives in, derived from the schema. */
  public static CreateTableRequest createTableRequest(String tableName) {
    var metadata = TABLE_SCHEMA.tableMetadata();
    var partitionKey = metadata.primaryPartitionKey();

    return CreateTableRequest.builder()
        .tableName(tableName)
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .attributeDefinitions(
            AttributeDefinition.builder()
                .attributeName(partitionKey)
                .attributeType(metadata.scalarAttributeType(partitionKey).orElseThrow())
                .build())
        .keySchema(
            KeySchemaElement.builder().attributeName(partitionKey).keyType(KeyType.HASH).build())
        .build();
  }

  // N.B.: the builder is required for the annotation above.
  public static final class Builder {
    private String websiteId;
    private Instant lastCheckedAt;

    @SuppressWarnings("unused")
    public Builder websiteId(String websiteId) {
      this.websiteId = websiteId;
      return this;
    }

    public Builder lastCheckedAt(Instant lastCheckedAt) {
      this.lastCheckedAt = lastCheckedAt;
      return this;
    }

    @SuppressWarnings("unused")
    public StateRecord build() {
      return new StateRecord(websiteId, lastCheckedAt);
    }
  }
}
