package family.blakey.uptimererer.core.db;

import java.time.Instant;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@DynamoDbImmutable(builder = StateRecord.Builder.class)
public record StateRecord(@DynamoDbPartitionKey String websiteId, Instant lastCheckedAt) {

    public static final TableSchema<StateRecord> TABLE_SCHEMA = TableSchema.fromClass(StateRecord.class);

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
