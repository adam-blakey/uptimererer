package family.blakey.uptimererer.core.db;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.ImmutableTableSchemaParams;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;

/**
 * The last observed state of one website: what the latest check saw ({@code status}, {@code
 * lastCheckedAt}) and when the status last flipped ({@code lastChangedAt}).
 */
@DynamoDbImmutable(builder = StateRecord.Builder.class)
public record StateRecord(
    @DynamoDbPartitionKey String websiteId,
    Instant lastCheckedAt,
    @DynamoDbConvertedBy(Status.Converter.class) Status status,
    Instant lastChangedAt) {

  // Built with this class's lookup so the SDK's generated lambda bridges resolve app classes
  // (Status.Converter, the Builder) even when the SDK sits in a parent classloader, as it does
  // under Quarkus dev-mode continuous testing.
  public static final TableSchema<StateRecord> TABLE_SCHEMA =
      TableSchema.fromImmutableClass(
          ImmutableTableSchemaParams.builder(StateRecord.class)
              .lookup(MethodHandles.lookup())
              .build());

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
    private Status status;
    private Instant lastChangedAt;

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
    public Builder status(Status status) {
      this.status = status;
      return this;
    }

    @SuppressWarnings("unused")
    public Builder lastChangedAt(Instant lastChangedAt) {
      this.lastChangedAt = lastChangedAt;
      return this;
    }

    @SuppressWarnings("unused")
    public StateRecord build() {
      return new StateRecord(websiteId, lastCheckedAt, status, lastChangedAt);
    }
  }
}
