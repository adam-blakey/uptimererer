package com.adamblakey.uptimererer.checker;

import com.adamblakey.uptimererer.events.CheckRequested;
import com.adamblakey.uptimererer.events.SiteConfig;
import com.adamblakey.uptimererer.json.Json;
import com.adamblakey.uptimererer.probe.Probe;
import com.adamblakey.uptimererer.probe.ProbeResult;
import com.adamblakey.uptimererer.state.StaleStateException;
import com.adamblakey.uptimererer.state.StateRecord;
import com.adamblakey.uptimererer.state.StateRepository;
import com.adamblakey.uptimererer.state.StateTransitions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The checker Lambda: consumes CheckRequested events from the uptimererer
 * EventBridge bus, probes the site over HTTP(S), and persists the resulting
 * up/down state to DynamoDB.
 */
public class CheckerHandler implements RequestHandler<ScheduledEvent, Void> {

    private final ObjectMapper mapper = Json.mapper();
    private final StateRepository repo;
    private final Probe probe;

    /** Constructed by the Lambda runtime: wires up real AWS clients from the environment. */
    public CheckerHandler() {
        this(new StateRepository(dynamoDbClient(), requireEnv("STATE_TABLE")), new Probe());
    }

    // The SDK's URL-connection HTTP client is chosen explicitly (over the also-present
    // Apache client) so client construction stays deterministic and cold starts stay lean.
    private static DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    CheckerHandler(StateRepository repo, Probe probe) {
        this.repo = repo;
        this.probe = probe;
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        CheckRequested request = CheckRequested.fromDetail(mapper, mapper.valueToTree(event.getDetail()));
        SiteConfig site = request.site().withDefaults();
        site.validate();

        Optional<StateRecord> previous = repo.get(site.id());
        ProbeResult result = probe.check(site);
        StateRecord next = StateTransitions.transition(previous.orElse(null), site, result, Instant.now());

        String previousLastCheckedAt = previous.map(StateRecord::lastCheckedAt).orElse(null);
        try {
            repo.putIfUnchanged(next, previousLastCheckedAt);
        } catch (StaleStateException e) {
            log(context, "site=%s discarding result: a concurrent check already wrote a newer one", site.id());
            return null;
        }

        log(context, "site=%s url=%s up=%b http=%d latency_ms=%d state=%s failures=%d err=%s",
                site.id(), site.url(), result.up(), result.httpStatus(), result.latencyMillis(),
                next.status(), next.consecutiveFailures(), result.error());
        return null;
    }

    private static void log(Context context, String format, Object... args) {
        String line = String.format(format, args);
        if (context != null) {
            context.getLogger().log(line);
        } else {
            System.out.println(line);
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required env " + name);
        }
        return value;
    }
}
