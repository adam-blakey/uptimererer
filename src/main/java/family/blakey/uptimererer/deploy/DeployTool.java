package family.blakey.uptimererer.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import family.blakey.uptimererer.core.db.StateRecord;
import family.blakey.uptimererer.core.events.CheckRequest;
import family.blakey.uptimererer.core.events.CheckRequestedEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.PutTargetsRequest;
import software.amazon.awssdk.services.eventbridge.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleResponse;
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.AddPermissionRequest;
import software.amazon.awssdk.services.lambda.model.Architecture;
import software.amazon.awssdk.services.lambda.model.CreateEventSourceMappingRequest;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.Environment;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.FunctionResponseType;
import software.amazon.awssdk.services.lambda.model.GetFunctionRequest;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsRequest;
import software.amazon.awssdk.services.lambda.model.ResourceConflictException;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.lambda.model.UpdateFunctionCodeRequest;
import software.amazon.awssdk.services.lambda.model.UpdateFunctionConfigurationRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

/**
 * Provisions the whole uptimererer stack from the architecture diagram — EventBridge scheduler →
 * SQS → decidererer Lambda (reading URL configs from Systems Manager) → event bus → checkererer
 * Lambda → DynamoDB state table → SNS notification topic — against a local AWS emulator (Floci or
 * LocalStack) on :4566. It exists so the real Lambda artifact ({@code target/function.zip}) can be
 * deployed and exercised locally with no extra tooling; the Makefile wraps the subcommands:
 *
 * <pre>
 *   deploy               provision/update everything from the built function.zip
 *   send -url URL        queue a one-off check request for URL
 *   tick                 queue a scheduler tick (checks every configured URL now)
 *   add-url -url URL     configure URL for checking on every tick (-name to override the id)
 *   urls                 print the configured URLs
 *   subscribe -email E   subscribe E to the notification topic
 *   state                print all site state records from DynamoDB
 * </pre>
 *
 * <p>Both Lambdas are created from the same zip; {@code QUARKUS_LAMBDA_HANDLER} picks the handler.
 *
 * <p>Configuration comes from the environment, all optional:
 *
 * <ul>
 *   <li>{@code AWS_ENDPOINT_URL} — emulator endpoint as seen from this machine (default {@code
 *       http://localhost:4566})
 *   <li>{@code LAMBDA_AWS_ENDPOINT} — emulator endpoint as seen from inside the Lambda containers
 *       (default {@code http://localhost.localstack.cloud:4566}, which LocalStack resolves; Floci
 *       may need something else)
 *   <li>{@code FUNCTION_ZIP} — path to the Lambda zip (default {@code target/function.zip})
 * </ul>
 */
public final class DeployTool {

  private static final String QUEUE_NAME = "uptimererer-checks";
  private static final String CHECKERERER_FUNCTION = "uptimererer-checkererer";
  private static final String DECIDERERER_FUNCTION = "uptimererer-decidererer";
  private static final String ROLE_NAME = "uptimererer-lambda-role";
  private static final String TOPIC_NAME = "uptimererer-notifications";
  private static final String TICK_RULE = "uptimererer-tick";
  private static final String CHECK_REQUESTED_RULE = "uptimererer-check-requested";

  // Quarkus' generic entry point; QUARKUS_LAMBDA_HANDLER selects the named handler.
  private static final String HANDLER =
      "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest";
  private static final String DEFAULT_FUNCTION_ZIP = "target/function.zip";
  private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);

  private final ObjectMapper mapper = new ObjectMapper();
  private final Properties applicationProperties = applicationProperties();
  private final String tableName = requiredProperty("uptimererer.state-table");
  private final String busName = requiredProperty("uptimererer.event-bus");
  private final String urlParameterPrefix = requiredProperty("uptimererer.url-parameter-prefix");
  private final DynamoDbClient ddb;
  private final IamClient iam;
  private final SqsClient sqs;
  private final LambdaClient lambda;
  private final EventBridgeClient events;
  private final SnsClient sns;
  private final SsmClient ssm;

  DeployTool(
      DynamoDbClient ddb,
      IamClient iam,
      SqsClient sqs,
      LambdaClient lambda,
      EventBridgeClient events,
      SnsClient sns,
      SsmClient ssm) {
    this.ddb = ddb;
    this.iam = iam;
    this.sqs = sqs;
    this.lambda = lambda;
    this.events = events;
    this.sns = sns;
    this.ssm = ssm;
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      fail("usage: deploy <deploy|send|tick|add-url|urls|subscribe|state> [flags]");
    }

    URI endpoint = URI.create(env("AWS_ENDPOINT_URL", "http://localhost:4566"));
    try (DynamoDbClient ddb = configure(DynamoDbClient.builder(), endpoint).build();
        IamClient iam = configure(IamClient.builder(), endpoint).region(Region.AWS_GLOBAL).build();
        SqsClient sqs = configure(SqsClient.builder(), endpoint).build();
        LambdaClient lambda = configure(LambdaClient.builder(), endpoint).build();
        EventBridgeClient events = configure(EventBridgeClient.builder(), endpoint).build();
        SnsClient sns = configure(SnsClient.builder(), endpoint).build();
        SsmClient ssm = configure(SsmClient.builder(), endpoint).build()) {

      DeployTool tool = new DeployTool(ddb, iam, sqs, lambda, events, sns, ssm);
      String[] flags = Arrays.copyOfRange(args, 1, args.length);
      switch (args[0]) {
        case "deploy" -> tool.deploy();
        case "send" -> tool.send(flags);
        case "tick" -> tool.tick();
        case "add-url" -> tool.addUrl(flags);
        case "urls" -> tool.urls();
        case "subscribe" -> tool.subscribe(flags);
        case "state" -> tool.state();
        default ->
            fail(
                "unknown subcommand '"
                    + args[0]
                    + "' (expected deploy, send, tick, add-url, urls, subscribe or state)");
      }
    } catch (Exception e) {
      fail(e.getMessage());
    }
  }

  /**
   * Points a client at the emulator with the same dummy credentials the dev profile uses. The
   * URL-connection HTTP client is picked explicitly because Quarkus puts several SDK HTTP clients
   * on the classpath, and leaving the choice ambiguous makes client construction fail.
   */
  private static <B extends AwsClientBuilder<B, ?> & SdkSyncClientBuilder<B, ?>> B configure(
      B builder, URI endpoint) {
    return builder
        .endpointOverride(endpoint)
        .region(Region.of(env("AWS_REGION", "us-east-1")))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
        .httpClientBuilder(UrlConnectionHttpClient.builder());
  }

  private void deploy() throws IOException {
    SdkBytes code = SdkBytes.fromByteArray(Files.readAllBytes(functionZipPath()));
    waitReady();

    String lambdaEndpoint = env("LAMBDA_AWS_ENDPOINT", "http://localhost.localstack.cloud:4566");

    String roleArn = ensureRole();
    ensureTable();
    String queueArn = ensureQueue();
    String topicArn = ensureTopic();
    ensureBus();

    ensureFunction(
        CHECKERERER_FUNCTION,
        roleArn,
        code,
        Map.of(
            "QUARKUS_LAMBDA_HANDLER", "checkererer",
            "QUARKUS_DYNAMODB_ENDPOINT_OVERRIDE", lambdaEndpoint,
            "QUARKUS_SNS_ENDPOINT_OVERRIDE", lambdaEndpoint,
            "UPTIMERERER_NOTIFICATION_TOPIC_ARN", topicArn));
    ensureFunction(
        DECIDERERER_FUNCTION,
        roleArn,
        code,
        Map.of(
            "QUARKUS_LAMBDA_HANDLER", "decidererer",
            "QUARKUS_SSM_ENDPOINT_OVERRIDE", lambdaEndpoint,
            "QUARKUS_EVENTBRIDGE_ENDPOINT_OVERRIDE", lambdaEndpoint));

    ensureEventSourceMapping(queueArn, DECIDERERER_FUNCTION);
    ensureTickRule(queueArn);
    ensureCheckRequestedRule();

    System.out.printf(
        "deployed: table=%s queue=%s bus=%s topic=%s functions=%s,%s%n",
        tableName, QUEUE_NAME, busName, TOPIC_NAME, DECIDERERER_FUNCTION, CHECKERERER_FUNCTION);
    System.out.println("try: make add-url URL=https://example.com, then make tick");
  }

  /**
   * Polls until the endpoint answers DynamoDB calls, so {@code deploy} can run right after startup.
   */
  private void waitReady() {
    Instant deadline = Instant.now().plus(READY_TIMEOUT);
    while (true) {
      try {
        ddb.listTables(ListTablesRequest.builder().limit(1).build());
        return;
      } catch (SdkException e) {
        if (Instant.now().isAfter(deadline)) {
          throw new IllegalStateException(
              "endpoint not ready after "
                  + READY_TIMEOUT
                  + " (is the emulator running? make emulator-floci): "
                  + e.getMessage(),
              e);
        }
        sleep(Duration.ofSeconds(1));
      }
    }
  }

  private String ensureRole() {
    String trust =
        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
    try {
      CreateRoleResponse created =
          iam.createRole(
              CreateRoleRequest.builder()
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
      ddb.createTable(StateRecord.createTableRequest(tableName));
      System.out.println("created table " + tableName);
    } catch (ResourceInUseException e) {
      // table already exists
    }
    ddb.waiter().waitUntilTableExists(b -> b.tableName(tableName));
  }

  private String ensureQueue() {
    String queueUrl =
        sqs.createQueue(CreateQueueRequest.builder().queueName(QUEUE_NAME).build()).queueUrl();
    return sqs.getQueueAttributes(
            GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build())
        .attributes()
        .get(QueueAttributeName.QUEUE_ARN);
  }

  private String ensureTopic() {
    // CreateTopic is idempotent: it returns the existing topic's ARN.
    return sns.createTopic(CreateTopicRequest.builder().name(TOPIC_NAME).build()).topicArn();
  }

  private void ensureBus() {
    try {
      events.createEventBus(b -> b.name(busName));
      System.out.println("created event bus " + busName);
    } catch (ResourceAlreadyExistsException e) {
      // bus already exists
    }
  }

  private void ensureFunction(String name, String roleArn, SdkBytes code, Map<String, String> env) {
    // The functions run the prod profile, which deliberately has no endpoint overrides baked
    // in — the env map points each client at the emulator as seen from inside the container.
    Environment environment = Environment.builder().variables(env).build();

    if (!functionExists(name)) {
      lambda.createFunction(
          CreateFunctionRequest.builder()
              .functionName(name)
              .role(roleArn)
              .runtime(Runtime.JAVA25)
              .handler(HANDLER)
              .architectures(Architecture.X86_64)
              .code(FunctionCode.builder().zipFile(code).build())
              .environment(environment)
              .timeout(30)
              .memorySize(512)
              .build());
      System.out.println("created function " + name);
      waitFunctionSettled(name);
      return;
    }

    lambda.updateFunctionCode(
        UpdateFunctionCodeRequest.builder().functionName(name).zipFile(code).build());
    waitFunctionSettled(name);
    lambda.updateFunctionConfiguration(
        UpdateFunctionConfigurationRequest.builder()
            .functionName(name)
            .role(roleArn)
            .environment(environment)
            .build());
    waitFunctionSettled(name);
    System.out.println("updated function " + name);
  }

  private boolean functionExists(String name) {
    try {
      lambda.getFunction(GetFunctionRequest.builder().functionName(name).build());
      return true;
    } catch (ResourceNotFoundException e) {
      return false;
    }
  }

  private void waitFunctionSettled(String name) {
    lambda.waiter().waitUntilFunctionActiveV2(b -> b.functionName(name));
    lambda.waiter().waitUntilFunctionUpdatedV2(b -> b.functionName(name));
  }

  private void ensureEventSourceMapping(String queueArn, String functionName) {
    // Checked explicitly rather than relying on ResourceConflictException: not every
    // emulator rejects a duplicate mapping, and a second one would double-deliver.
    boolean exists =
        !lambda
            .listEventSourceMappings(
                ListEventSourceMappingsRequest.builder()
                    .eventSourceArn(queueArn)
                    .functionName(functionName)
                    .build())
            .eventSourceMappings()
            .isEmpty();
    if (exists) {
      return;
    }
    try {
      lambda.createEventSourceMapping(
          CreateEventSourceMappingRequest.builder()
              .eventSourceArn(queueArn)
              .functionName(functionName)
              .batchSize(10)
              // the handler returns SQSBatchResponse, so only failed messages are retried
              .functionResponseTypes(FunctionResponseType.REPORT_BATCH_ITEM_FAILURES)
              .build());
      System.out.println("created event source mapping " + QUEUE_NAME + " -> " + functionName);
    } catch (ResourceConflictException e) {
      // mapping already exists
    }
  }

  /** The diagram's "runs every minute": a scheduled rule on the default bus ticks the queue. */
  private void ensureTickRule(String queueArn) {
    String ruleArn =
        events
            .putRule(
                PutRuleRequest.builder()
                    .name(TICK_RULE)
                    .scheduleExpression("rate(1 minute)")
                    .build())
            .ruleArn();
    events.putTargets(
        PutTargetsRequest.builder()
            .rule(TICK_RULE)
            .targets(Target.builder().id("checks-queue").arn(queueArn).build())
            .build());

    // Emulators don't enforce queue policies, but real EventBridge can't send without one.
    String queueUrl =
        sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(QUEUE_NAME).build()).queueUrl();
    String policy =
        ("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"events.amazonaws.com\"},"
                + "\"Action\":\"sqs:SendMessage\",\"Resource\":\"%s\","
                + "\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"%s\"}}}]}")
            .formatted(queueArn, ruleArn);
    sqs.setQueueAttributes(
        SetQueueAttributesRequest.builder()
            .queueUrl(queueUrl)
            .attributes(Map.of(QueueAttributeName.POLICY, policy))
            .build());
    System.out.println("scheduled rule " + TICK_RULE + " -> " + QUEUE_NAME + " (rate(1 minute))");
  }

  /** Routes the decidererer's pending requests from the custom bus into the checkererer. */
  private void ensureCheckRequestedRule() throws IOException {
    String pattern =
        mapper.writeValueAsString(
            Map.of(
                "source", List.of(CheckRequestedEvent.SOURCE),
                "detail-type", List.of(CheckRequestedEvent.DETAIL_TYPE)));
    String ruleArn =
        events
            .putRule(
                PutRuleRequest.builder()
                    .name(CHECK_REQUESTED_RULE)
                    .eventBusName(busName)
                    .eventPattern(pattern)
                    .build())
            .ruleArn();

    String functionArn =
        lambda
            .getFunction(GetFunctionRequest.builder().functionName(CHECKERERER_FUNCTION).build())
            .configuration()
            .functionArn();
    events.putTargets(
        PutTargetsRequest.builder()
            .eventBusName(busName)
            .rule(CHECK_REQUESTED_RULE)
            .targets(Target.builder().id("checkererer").arn(functionArn).build())
            .build());

    try {
      lambda.addPermission(
          AddPermissionRequest.builder()
              .functionName(CHECKERERER_FUNCTION)
              .statementId("uptimererer-events-invoke")
              .action("lambda:InvokeFunction")
              .principal("events.amazonaws.com")
              .sourceArn(ruleArn)
              .build());
    } catch (ResourceConflictException e) {
      // permission already granted
    }
    System.out.println("bus rule " + CHECK_REQUESTED_RULE + " -> " + CHECKERERER_FUNCTION);
  }

  private void send(String[] args) throws IOException {
    Map<String, String> flags = parseFlags(args);

    String url = flags.get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("send: missing required -url");
    }
    // Fail fast with the same validation the decidererer applies.
    CheckRequest request = CheckRequest.Validator.validate(new CheckRequest(url));

    sqs.sendMessage(
        SendMessageRequest.builder()
            .queueUrl(queueUrl())
            .messageBody(mapper.writeValueAsString(request))
            .build());

    System.out.printf("queued check for %s%n", url);
  }

  /** What the scheduler does every minute, on demand: any body without a url is a tick. */
  private void tick() {
    sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl()).messageBody("{}").build());
    System.out.println("queued a tick (checks every configured url)");
  }

  private void addUrl(String[] args) {
    Map<String, String> flags = parseFlags(args);

    String url = flags.get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("add-url: missing required -url");
    }
    CheckRequest request = CheckRequest.Validator.validate(new CheckRequest(url));

    String name = flags.getOrDefault("name", URI.create(request.url()).getHost());
    String parameter = urlParameterPrefix + name;
    ssm.putParameter(
        PutParameterRequest.builder()
            .name(parameter)
            .value(request.url())
            .type(ParameterType.STRING)
            .overwrite(true)
            .build());

    System.out.printf("configured %s = %s (checked on every tick)%n", parameter, request.url());
  }

  private void urls() {
    int count = 0;
    for (var page :
        ssm.getParametersByPathPaginator(b -> b.path(urlParameterPrefix).recursive(true))) {
      for (var parameter : page.parameters()) {
        System.out.printf("%s = %s%n", parameter.name(), parameter.value());
        count++;
      }
    }
    if (count == 0) {
      System.out.println("no urls configured yet (make add-url URL=https://example.com)");
    }
  }

  private void subscribe(String[] args) {
    Map<String, String> flags = parseFlags(args);

    String email = flags.get("email");
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("subscribe: missing required -email");
    }

    String topicArn = ensureTopic();
    sns.subscribe(
        SubscribeRequest.builder().topicArn(topicArn).protocol("email").endpoint(email).build());
    System.out.printf(
        "subscribed %s to %s (real AWS sends a confirmation email; emulators auto-confirm)%n",
        email, TOPIC_NAME);
  }

  private void state() {
    DynamoDbTable<StateRecord> table =
        DynamoDbEnhancedClient.builder()
            .dynamoDbClient(ddb)
            .build()
            .table(tableName, StateRecord.TABLE_SCHEMA);

    int count = 0;
    try {
      for (StateRecord record : table.scan().items()) {
        System.out.println(record);
        count++;
      }
    } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
      throw new IllegalStateException(
          "table " + tableName + " not found (run `make deploy-floci` first)");
    }
    if (count == 0) {
      System.out.println("no site state yet (send a check first: make send URL=...)");
    }
  }

  private String queueUrl() {
    try {
      return sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(QUEUE_NAME).build()).queueUrl();
    } catch (QueueDoesNotExistException e) {
      throw new IllegalStateException(
          "queue " + QUEUE_NAME + " not found (run `make deploy-floci` first)");
    }
  }

  private Path functionZipPath() {
    Path path = Path.of(env("FUNCTION_ZIP", DEFAULT_FUNCTION_ZIP));
    if (!Files.exists(path)) {
      throw new IllegalStateException(
          "function zip not found at " + path + " (run `make build` first, or set FUNCTION_ZIP)");
    }
    return path;
  }

  /** The names the application itself is configured with, so the two can't drift. */
  private static Properties applicationProperties() {
    Properties properties = new Properties();
    try (InputStream in = DeployTool.class.getResourceAsStream("/application.properties")) {
      properties.load(in);
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("could not read application.properties from classpath", e);
    }
    return properties;
  }

  private String requiredProperty(String name) {
    String value = applicationProperties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " not set in application.properties");
    }
    return value;
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value != null && !value.isBlank() ? value : fallback;
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
