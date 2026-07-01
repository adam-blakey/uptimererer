package com.adamblakey.uptimererer.state;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single DynamoDB item kept per site (partition key: {@code siteId}).
 *
 * <p>Timestamps are ISO-8601 strings. The nullable fields
 * ({@code lastStatusChangeAt}, {@code lastError}) are omitted from JSON when
 * absent, mirroring the table's optional attributes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StateRecord(
        String siteId,
        Status status,
        int consecutiveFailures,
        String lastCheckedAt,
        String lastStatusChangeAt,
        int lastHttpStatus,
        long latencyMs,
        String lastError) {
}
