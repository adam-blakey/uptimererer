package com.adamblakey.uptimererer.state;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/** Reads and writes site {@link StateRecord}s in DynamoDB. */
public final class StateRepository {

    private final DynamoDbClient db;
    private final String table;

    public StateRepository(DynamoDbClient db, String table) {
        this.db = db;
        this.table = table;
    }

    /** Returns the record for {@code siteId}, or empty when it has never been checked. */
    public Optional<StateRecord> get(String siteId) {
        Map<String, AttributeValue> item = db.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(Map.of("siteId", AttributeValue.fromS(siteId)))
                .consistentRead(true)
                .build()).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromItem(item));
    }

    /**
     * Writes {@code record}, but only if the stored item's {@code lastCheckedAt}
     * still equals {@code previousLastCheckedAt} (null/empty means the item must
     * not exist yet), so a stale overlapping invocation can't clobber a newer
     * result.
     *
     * @throws StaleStateException if the conditional check fails
     */
    public void putIfUnchanged(StateRecord record, String previousLastCheckedAt) throws StaleStateException {
        PutItemRequest.Builder request = PutItemRequest.builder()
                .tableName(table)
                .item(toItem(record));
        if (previousLastCheckedAt == null || previousLastCheckedAt.isEmpty()) {
            request.conditionExpression("attribute_not_exists(siteId)");
        } else {
            request.conditionExpression("lastCheckedAt = :prev")
                    .expressionAttributeValues(Map.of(":prev", AttributeValue.fromS(previousLastCheckedAt)));
        }

        try {
            db.putItem(request.build());
        } catch (ConditionalCheckFailedException e) {
            throw new StaleStateException("state for site '" + record.siteId() + "' changed since read");
        }
    }

    private static Map<String, AttributeValue> toItem(StateRecord r) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("siteId", AttributeValue.fromS(r.siteId()));
        item.put("status", AttributeValue.fromS(r.status().name()));
        item.put("consecutiveFailures", AttributeValue.fromN(Integer.toString(r.consecutiveFailures())));
        item.put("lastCheckedAt", AttributeValue.fromS(r.lastCheckedAt()));
        item.put("lastHttpStatus", AttributeValue.fromN(Integer.toString(r.lastHttpStatus())));
        item.put("latencyMs", AttributeValue.fromN(Long.toString(r.latencyMs())));
        if (r.lastStatusChangeAt() != null) {
            item.put("lastStatusChangeAt", AttributeValue.fromS(r.lastStatusChangeAt()));
        }
        if (r.lastError() != null) {
            item.put("lastError", AttributeValue.fromS(r.lastError()));
        }
        return item;
    }

    /** Maps a raw DynamoDB item back to a {@link StateRecord}. */
    public static StateRecord fromItem(Map<String, AttributeValue> item) {
        return new StateRecord(
                string(item, "siteId"),
                item.containsKey("status") ? Status.valueOf(item.get("status").s()) : Status.UNKNOWN,
                (int) number(item, "consecutiveFailures"),
                string(item, "lastCheckedAt"),
                string(item, "lastStatusChangeAt"),
                (int) number(item, "lastHttpStatus"),
                number(item, "latencyMs"),
                string(item, "lastError"));
    }

    private static String string(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private static long number(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return (value == null || value.n() == null) ? 0L : Long.parseLong(value.n());
    }
}
