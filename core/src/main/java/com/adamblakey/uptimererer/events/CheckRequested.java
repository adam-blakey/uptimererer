package com.adamblakey.uptimererer.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

/**
 * Detail payload of a CheckRequested EventBridge event: the full
 * {@link SiteConfig} plus the time the dispatcher requested the check.
 *
 * <p>On the wire the site config is flattened alongside {@code requestedAt}
 * (a single JSON object), so the checker can parse the detail straight into a
 * {@link SiteConfig}.
 */
public record CheckRequested(SiteConfig site, Instant requestedAt) {

    /** Serialises to the flat wire form: the site fields plus {@code requestedAt}. */
    public ObjectNode toDetail(ObjectMapper mapper) {
        ObjectNode node = mapper.valueToTree(site);
        node.put("requestedAt", requestedAt.toString());
        return node;
    }

    /** Parses the flat wire form produced by {@link #toDetail(ObjectMapper)}. */
    public static CheckRequested fromDetail(ObjectMapper mapper, JsonNode detail) {
        SiteConfig site = mapper.convertValue(detail, SiteConfig.class);
        Instant requestedAt = detail.hasNonNull("requestedAt")
                ? Instant.parse(detail.get("requestedAt").asText())
                : null;
        return new CheckRequested(site, requestedAt);
    }
}
