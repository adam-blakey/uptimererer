package family.blakey.uptimererer.deploy;

import com.adamblakey.uptimererer.events.CheckRequested;
import com.adamblakey.uptimererer.events.Events;
import com.adamblakey.uptimererer.events.SiteConfig;
import com.adamblakey.uptimererer.json.Json;
import com.adamblakey.uptimererer.state.StateRecord;
import com.adamblakey.uptimererer.state.StateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.CreateEventBusRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;
import software.amazon.awssdk.services.eventbridge.model.PutRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.PutRuleResponse;
import software.amazon.awssdk.services.eventbridge.model.PutTargetsRequest;
import software.amazon.awssdk.services.eventbridge.model.RuleState;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleResponse;
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.AddPermissionRequest;
import software.amazon.awssdk.services.lambda.model.Architecture;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.CreateFunctionResponse;
import software.amazon.awssdk.services.lambda.model.Environment;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetFunctionRequest;
import software.amazon.awssdk.services.lambda.model.ResourceConflictException;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.lambda.model.UpdateFunctionCodeRequest;
import software.amazon.awssdk.services.lambda.model.UpdateFunctionCodeResponse;
import software.amazon.awssdk.services.lambda.model.UpdateFunctionConfigurationRequest;

/**
 * Provisions the checker stack — DynamoDB table, EventBridge bus + rule, and
 * the checker Lambda — against any AWS-compatible endpoint. It exists so the
 * stack can be stood up on the local Floci emulator with no extra tooling:
 * point {@code AWS_ENDPOINT_URL} at the emulator (the Makefile does this) and
 * run the subcommands.
 *
 * <pre>
 *   deploy   provision/update everything from the built checker jar
 *   send -url URL [-id ID] [-failures N]
 *            put a CheckRequested event on the bus
 *   state    print all site state records from DynamoDB
 * </pre>
 */
public final class DeployTool {

    private static final String TABLE_NAME = "uptimererer-state";
    private static final String BUS_NAME = "uptimererer-bus";
    private static final String RULE_NAME = "uptimererer-check-requested";
    private static final String FUNCTION_NAME = "uptimererer-checker";
    private static final String ROLE_NAME = "uptimererer-checker-role";

    private static final String HANDLER = "com.adamblakey.uptimererer.checker.CheckerHandler::handleRequest";
    private static final String DEFAULT_CHECKER_JAR = "checker/target/uptimererer-checker.jar";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);

    private final ObjectMapper mapper = Json.mapper();
    private final DynamoDbClient ddb;
    private final IamClient iam;
    private final EventBridgeClient eb;
    private final LambdaClient lambda;

    DeployTool(DynamoDbClient ddb, IamClient iam, EventBridgeClient eb, LambdaClient lambda) {
        this.ddb = ddb;
        this.iam = iam;
        this.eb = eb;
        this.lambda = lambda;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            fail("usage: deploy <deploy|send|state> [flags]");
        }

        // Pick the URL-connection HTTP client explicitly: the Apache client is also on the
        // classpath, and leaving the choice ambiguous makes client construction fail.
        UrlConnectionHttpClient.Builder http = UrlConnectionHttpClient.builder();
        try (DynamoDbClient ddb = DynamoDbClient.builder().httpClientBuilder(http).build();
             IamClient iam = IamClient.builder().region(Region.AWS_GLOBAL).httpClientBuilder(http).build();
             EventBridgeClient eb = EventBridgeClient.builder().httpClientBuilder(http).build();
             LambdaClient lambda = LambdaClient.builder().httpClientBuilder(http).build()) {

            DeployTool tool = new DeployTool(ddb, iam, eb, lambda);
            switch (args[0]) {
                case "deploy" -> tool.deploy();
                case "send" -> tool.send(Arrays.copyOfRange(args, 1, args.length));
                case "state" -> tool.state();
                default -> fail("unknown subcommand '" + args[0] + "' (expected deploy, send or state)");
            }
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    private void deploy() throws IOException {
        waitReady();

        String roleArn = ensureRole();
        ensureTable();
        String functionArn = ensureFunction(roleArn);
        ensureBusAndRule(functionArn);

        System.out.printf("deployed: table=%s bus=%s function=%s%n", TABLE_NAME, BUS_NAME, FUNCTION_NAME);
        System.out.println("try: make check URL=https://example.com");
    }

    /** Polls until the endpoint answers DynamoDB calls, so {@code deploy} can run right after startup. */
    private void waitReady() {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        while (true) {
            try {
                ddb.listTables(ListTablesRequest.builder().limit(1).build());
                return;
            } catch (SdkException e) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("endpoint not ready after " + READY_TIMEOUT
                            + " (is the emulator running? make local-up): " + e.getMessage(), e);
                }
                sleep(Duration.ofSeconds(1));
            }
        }
    }

    private String ensureRole() {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        try {
            CreateRoleResponse created = iam.createRole(CreateRoleRequest.builder()
                    .roleName(ROLE_NAME)
                    .assumeRolePolicyDocument(trust)
                    .build());
            System.out.println("created role " + ROLE_NAME);
            return created.role().arn();
        } catch (EntityAlreadyExistsException e) {
            return iam.getRole(GetRoleRequest.builder().roleName(ROLE_NAME).build()).role().arn();
        }
    }

    private void ensureTable() {
        try {
            ddb.createTable(CreateTableRequest.builder()
                    .tableName(TABLE_NAME)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("siteId")
                            .attributeType(ScalarAttributeType.S)
                            .build())
                    .keySchema(KeySchemaElement.builder()
                            .attributeName("siteId")
                            .keyType(KeyType.HASH)
                            .build())
                    .build());
            System.out.println("created table " + TABLE_NAME);
        } catch (ResourceInUseException e) {
            // table already exists
        }
        ddb.waiter().waitUntilTableExists(b -> b.tableName(TABLE_NAME));
    }

    private String ensureFunction(String roleArn) throws IOException {
        SdkBytes code = SdkBytes.fromByteArray(Files.readAllBytes(checkerJarPath()));
        Environment env = Environment.builder().variables(Map.of("STATE_TABLE", TABLE_NAME)).build();

        if (!functionExists()) {
            CreateFunctionResponse created = lambda.createFunction(CreateFunctionRequest.builder()
                    .functionName(FUNCTION_NAME)
                    .role(roleArn)
                    .runtime(Runtime.JAVA21)
                    .handler(HANDLER)
                    .architectures(Architecture.X86_64)
                    .code(FunctionCode.builder().zipFile(code).build())
                    .environment(env)
                    .timeout(30)
                    .memorySize(512)
                    .build());
            System.out.println("created function " + FUNCTION_NAME);
            waitFunctionSettled();
            return created.functionArn();
        }

        UpdateFunctionCodeResponse updated = lambda.updateFunctionCode(UpdateFunctionCodeRequest.builder()
                .functionName(FUNCTION_NAME)
                .zipFile(code)
                .build());
        waitFunctionSettled();
        lambda.updateFunctionConfiguration(UpdateFunctionConfigurationRequest.builder()
                .functionName(FUNCTION_NAME)
                .role(roleArn)
                .environment(env)
                .build());
        waitFunctionSettled();
        System.out.println("updated function " + FUNCTION_NAME);
        return updated.functionArn();
    }

    private boolean functionExists() {
        try {
            lambda.getFunction(GetFunctionRequest.builder().functionName(FUNCTION_NAME).build());
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    private void waitFunctionSettled() {
        lambda.waiter().waitUntilFunctionActiveV2(b -> b.functionName(FUNCTION_NAME));
        lambda.waiter().waitUntilFunctionUpdatedV2(b -> b.functionName(FUNCTION_NAME));
    }

    private void ensureBusAndRule(String functionArn) {
        try {
            eb.createEventBus(CreateEventBusRequest.builder().name(BUS_NAME).build());
            System.out.println("created event bus " + BUS_NAME);
        } catch (software.amazon.awssdk.services.eventbridge.model.ResourceAlreadyExistsException e) {
            // bus already exists
        }

        String pattern = String.format("{\"source\":[\"%s\"],\"detail-type\":[\"%s\"]}",
                Events.SOURCE, Events.DETAIL_TYPE_CHECK_REQUESTED);
        PutRuleResponse rule = eb.putRule(PutRuleRequest.builder()
                .name(RULE_NAME)
                .eventBusName(BUS_NAME)
                .eventPattern(pattern)
                .state(RuleState.ENABLED)
                .build());

        eb.putTargets(PutTargetsRequest.builder()
                .rule(RULE_NAME)
                .eventBusName(BUS_NAME)
                .targets(Target.builder().id("checker").arn(functionArn).build())
                .build());

        try {
            lambda.addPermission(AddPermissionRequest.builder()
                    .functionName(FUNCTION_NAME)
                    .statementId("uptimererer-eventbridge")
                    .action("lambda:InvokeFunction")
                    .principal("events.amazonaws.com")
                    .sourceArn(rule.ruleArn())
                    .build());
        } catch (ResourceConflictException e) {
            // permission already added
        }
    }

    private void send(String[] args) {
        Map<String, String> flags = parseFlags(args);

        String url = flags.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("send: missing required -url");
        }
        String id = flags.get("id");
        if (id == null || id.isBlank()) {
            String host = URI.create(url).getHost();
            if (host == null) {
                throw new IllegalArgumentException("send: cannot derive -id from url " + url);
            }
            id = host;
        }
        int failures = flags.containsKey("failures") && !flags.get("failures").isBlank()
                ? Integer.parseInt(flags.get("failures"))
                : 0;

        SiteConfig site = new SiteConfig(id, url, null, 0, null, failures);
        CheckRequested request = new CheckRequested(site, Instant.now());

        PutEventsResponse response = eb.putEvents(PutEventsRequest.builder()
                .entries(PutEventsRequestEntry.builder()
                        .eventBusName(BUS_NAME)
                        .source(Events.SOURCE)
                        .detailType(Events.DETAIL_TYPE_CHECK_REQUESTED)
                        .detail(request.toDetail(mapper).toString())
                        .build())
                .build());
        if (response.failedEntryCount() != null && response.failedEntryCount() > 0) {
            PutEventsResultEntry entry = response.entries().get(0);
            throw new IllegalStateException("put events failed: " + entry.errorCode() + " " + entry.errorMessage());
        }

        System.out.printf("sent CheckRequested for %s (%s)%n", id, url);
    }

    private void state() throws IOException {
        int count = 0;
        for (ScanResponse page : ddb.scanPaginator(ScanRequest.builder().tableName(TABLE_NAME).build())) {
            for (Map<String, AttributeValue> item : page.items()) {
                StateRecord record = StateRepository.fromItem(item);
                System.out.println(mapper.writeValueAsString(record));
                count++;
            }
        }
        if (count == 0) {
            System.out.println("no site state yet (send a check first: make check URL=...)");
        }
    }

    private Path checkerJarPath() {
        String override = System.getenv("CHECKER_JAR");
        Path path = Path.of(override != null && !override.isBlank() ? override : DEFAULT_CHECKER_JAR);
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "checker jar not found at " + path + " (run `make build` first, or set CHECKER_JAR)");
        }
        return path;
    }

    /** Parses {@code -key value} and {@code -key=value} pairs into a map. */
    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-")) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }
            String key = arg.substring(1);
            String value;
            int eq = key.indexOf('=');
            if (eq >= 0) {
                value = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else if (i + 1 < args.length) {
                value = args[++i];
            } else {
                value = "";
            }
            flags.put(key, value);
        }
        return flags;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }

    private static void fail(String message) {
        System.err.println("error: " + message);
        System.exit(1);
    }
}
