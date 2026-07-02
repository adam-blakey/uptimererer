package family.blakey.uptimererer.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import family.blakey.uptimererer.checkererer.Request;
import family.blakey.uptimererer.core.db.StateRecord;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
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
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleResponse;
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Provisions the checkererer stack — DynamoDB state table, SQS check queue, and the checkererer
 * Lambda wired to it — against a local AWS emulator (Floci or LocalStack) on :4566. It exists so
 * the real Lambda artifact ({@code target/function.zip}) can be deployed and exercised locally with
 * no extra tooling; the Makefile wraps the subcommands:
 *
 * <pre>
 *   deploy         provision/update everything from the built function.zip
 *   send -url URL  queue a check request for URL
 *   state          print all site state records from DynamoDB
 * </pre>
 *
 * <p>Configuration comes from the environment, all optional:
 *
 * <ul>
 *   <li>{@code AWS_ENDPOINT_URL} — emulator endpoint as seen from this machine (default {@code
 *       http://localhost:4566})
 *   <li>{@code LAMBDA_DYNAMODB_ENDPOINT} — emulator endpoint as seen from inside the Lambda
 *       container (default {@code http://localhost.localstack.cloud:4566}, which LocalStack
 *       resolves; Floci may need something else)
 *   <li>{@code FUNCTION_ZIP} — path to the Lambda zip (default {@code target/function.zip})
 * </ul>
 */
public final class DeployTool {

  private static final String QUEUE_NAME = "uptimererer-checks";
  private static final String FUNCTION_NAME = "uptimererer-checkererer";
  private static final String ROLE_NAME = "uptimererer-checkererer-role";

  // Quarkus' generic entry point; it locates CheckerererHandler itself.
  private static final String HANDLER =
      "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest";
  private static final String DEFAULT_FUNCTION_ZIP = "target/function.zip";
  private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);

  private final ObjectMapper mapper = new ObjectMapper();
  private final String tableName = stateTableName();
  private final DynamoDbClient ddb;
  private final IamClient iam;
  private final SqsClient sqs;
  private final LambdaClient lambda;

  DeployTool(DynamoDbClient ddb, IamClient iam, SqsClient sqs, LambdaClient lambda) {
    this.ddb = ddb;
    this.iam = iam;
    this.sqs = sqs;
    this.lambda = lambda;
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      fail("usage: deploy <deploy|send|state> [flags]");
    }

    URI endpoint = URI.create(env("AWS_ENDPOINT_URL", "http://localhost:4566"));
    try (DynamoDbClient ddb = configure(DynamoDbClient.builder(), endpoint).build();
        IamClient iam = configure(IamClient.builder(), endpoint).region(Region.AWS_GLOBAL).build();
        SqsClient sqs = configure(SqsClient.builder(), endpoint).build();
        LambdaClient lambda = configure(LambdaClient.builder(), endpoint).build()) {

      DeployTool tool = new DeployTool(ddb, iam, sqs, lambda);
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

    String roleArn = ensureRole();
    ensureTable();
    String queueArn = ensureQueue();
    ensureFunction(roleArn, code);
    ensureEventSourceMapping(queueArn);

    System.out.printf(
        "deployed: table=%s queue=%s function=%s%n", tableName, QUEUE_NAME, FUNCTION_NAME);
    System.out.println("try: make send URL=https://example.com");
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

  private void ensureFunction(String roleArn, SdkBytes code) {
    // The function runs the prod profile, which deliberately has no DynamoDB endpoint override
    // baked in — point it at the emulator as seen from inside the Lambda container.
    Environment env =
        Environment.builder()
            .variables(
                Map.of(
                    "QUARKUS_DYNAMODB_ENDPOINT_OVERRIDE",
                    env("LAMBDA_DYNAMODB_ENDPOINT", "http://localhost.localstack.cloud:4566")))
            .build();

    if (!functionExists()) {
      lambda.createFunction(
          CreateFunctionRequest.builder()
              .functionName(FUNCTION_NAME)
              .role(roleArn)
              .runtime(Runtime.JAVA25)
              .handler(HANDLER)
              .architectures(Architecture.X86_64)
              .code(FunctionCode.builder().zipFile(code).build())
              .environment(env)
              .timeout(30)
              .memorySize(512)
              .build());
      System.out.println("created function " + FUNCTION_NAME);
      waitFunctionSettled();
      return;
    }

    lambda.updateFunctionCode(
        UpdateFunctionCodeRequest.builder().functionName(FUNCTION_NAME).zipFile(code).build());
    waitFunctionSettled();
    lambda.updateFunctionConfiguration(
        UpdateFunctionConfigurationRequest.builder()
            .functionName(FUNCTION_NAME)
            .role(roleArn)
            .environment(env)
            .build());
    waitFunctionSettled();
    System.out.println("updated function " + FUNCTION_NAME);
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

  private void ensureEventSourceMapping(String queueArn) {
    // Checked explicitly rather than relying on ResourceConflictException: not every
    // emulator rejects a duplicate mapping, and a second one would double-deliver.
    boolean exists =
        !lambda
            .listEventSourceMappings(
                ListEventSourceMappingsRequest.builder()
                    .eventSourceArn(queueArn)
                    .functionName(FUNCTION_NAME)
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
              .functionName(FUNCTION_NAME)
              .batchSize(10)
              // the handler returns SQSBatchResponse, so only failed messages are retried
              .functionResponseTypes(FunctionResponseType.REPORT_BATCH_ITEM_FAILURES)
              .build());
      System.out.println("created event source mapping " + QUEUE_NAME + " -> " + FUNCTION_NAME);
    } catch (ResourceConflictException e) {
      // mapping already exists
    }
  }

  private void send(String[] args) throws IOException {
    Map<String, String> flags = parseFlags(args);

    String url = flags.get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("send: missing required -url");
    }
    // Fail fast with the same validation the handler applies.
    Request request = Request.Validator.validate(new Request(url));

    String queueUrl;
    try {
      queueUrl =
          sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(QUEUE_NAME).build()).queueUrl();
    } catch (QueueDoesNotExistException e) {
      throw new IllegalStateException(
          "queue " + QUEUE_NAME + " not found (run `make deploy-floci` first)");
    }
    sqs.sendMessage(
        SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(mapper.writeValueAsString(request))
            .build());

    System.out.printf("queued check for %s%n", url);
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

  private Path functionZipPath() {
    Path path = Path.of(env("FUNCTION_ZIP", DEFAULT_FUNCTION_ZIP));
    if (!Files.exists(path)) {
      throw new IllegalStateException(
          "function zip not found at " + path + " (run `make build` first, or set FUNCTION_ZIP)");
    }
    return path;
  }

  /** The table name the application itself is configured with, so the two can't drift. */
  private static String stateTableName() {
    Properties properties = new Properties();
    try (InputStream in = DeployTool.class.getResourceAsStream("/application.properties")) {
      properties.load(in);
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("could not read application.properties from classpath", e);
    }
    String table = properties.getProperty("uptimererer.state-table");
    if (table == null || table.isBlank()) {
      throw new IllegalStateException("uptimererer.state-table not set in application.properties");
    }
    return table;
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
