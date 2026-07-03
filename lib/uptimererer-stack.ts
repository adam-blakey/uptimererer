import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as lambdaEventSources from 'aws-cdk-lib/aws-lambda-event-sources';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as snsSubscriptions from 'aws-cdk-lib/aws-sns-subscriptions';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as nag from 'cdk-nag';
import * as path from 'path';

export interface UptimerererStackProps extends cdk.StackProps {
  environment: 'localstack' | 'aws';
}

export class UptimerererStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: UptimerererStackProps) {
    super(scope, id, props);

    const isLocalstack = props.environment === 'localstack';
    // Endpoint LocalStack Lambdas use to reach other LocalStack services from
    // inside their containers (plain localhost:4566 is not reachable there).
    const localstackEndpoint = 'http://localhost.localstack.cloud:4566';

    const logRetention = isLocalstack
      ? logs.RetentionDays.ONE_DAY
      : logs.RetentionDays.ONE_MONTH;

    // DynamoDB table for storing uptime check results and current site state
    const uptimeTable = new dynamodb.Table(this, 'UptimeChecksTable', {
      tableName: isLocalstack ? 'uptime_checks' : undefined,
      partitionKey: {
        name: 'pk',
        type: dynamodb.AttributeType.STRING,
      },
      sortKey: {
        name: 'sk',
        type: dynamodb.AttributeType.STRING,
      },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: isLocalstack
        ? cdk.RemovalPolicy.DESTROY
        : cdk.RemovalPolicy.RETAIN,
    });

    // SSM parameter storing the tracked-URL configs as a JSON string array
    const urlsParameter = new ssm.StringParameter(this, 'UrlConfigsParameter', {
      parameterName: '/uptimererer/urls',
      description: 'JSON array of URLs tracked by uptimererer',
      stringValue: JSON.stringify(['https://example.com']),
    });

    // SNS topic that emails subscribers when a site goes offline / recovers
    const alertsTopic = new sns.Topic(this, 'AlertsTopic', {
      topicName: isLocalstack ? 'uptimererer-alerts' : undefined,
      displayName: 'uptimererer alerts',
    });

    const alertEmail = this.node.tryGetContext('alertEmail');
    if (alertEmail) {
      alertsTopic.addSubscription(new snsSubscriptions.EmailSubscription(alertEmail));
    }

    // Custom EventBridge bus carrying pending check requests
    const checksBus = new events.EventBus(this, 'ChecksBus', {
      eventBusName: isLocalstack ? 'uptimererer-bus' : undefined,
    });

    // SQS queue fed by the schedule; ticks are consumed by the dispatcher
    const ticksDlq = new sqs.Queue(this, 'TicksDeadLetterQueue', {
      queueName: isLocalstack ? 'uptimererer-ticks-dlq' : undefined,
      retentionPeriod: cdk.Duration.days(14),
    });

    const ticksQueue = new sqs.Queue(this, 'TicksQueue', {
      queueName: isLocalstack ? 'uptimererer-ticks' : undefined,
      visibilityTimeout: cdk.Duration.minutes(3),
      deadLetterQueue: {
        queue: ticksDlq,
        maxReceiveCount: 3,
      },
    });

    // EventBridge schedule: drop a tick onto the queue every minute
    new events.Rule(this, 'TickRule', {
      ruleName: isLocalstack ? 'uptimererer-tick' : undefined,
      description: 'Ticks the uptimererer dispatcher every minute',
      schedule: events.Schedule.rate(cdk.Duration.minutes(1)),
      targets: [new targets.SqsQueue(ticksQueue)],
    });

    // Lambda that decides which requests to make: reads URL configs from SSM
    // and emits one check.requested event per URL onto the custom bus
    const dispatcherLambda = new lambda.Function(this, 'DispatcherererLambda', {
      functionName: isLocalstack ? 'dispatchererer-localstack' : 'dispatchererer',
      runtime: lambda.Runtime.PROVIDED_AL2023,
      architecture: lambda.Architecture.X86_64,
      handler: 'bootstrap',
      code: lambda.Code.fromAsset(path.join(__dirname, '../lambda/dispatchererer')),
      timeout: cdk.Duration.seconds(30),
      memorySize: 128,
      environment: {
        URLS_PARAMETER: urlsParameter.parameterName,
        EVENT_BUS_NAME: checksBus.eventBusName,
        ...(isLocalstack && { AWS_ENDPOINT: localstackEndpoint }),
      },
      logGroup: new logs.LogGroup(this, 'DispatcherererLogGroup', {
        logGroupName: isLocalstack
          ? '/aws/lambda/dispatchererer-localstack'
          : '/aws/lambda/dispatchererer',
        retention: logRetention,
      }),
    });

    dispatcherLambda.addEventSource(new lambdaEventSources.SqsEventSource(ticksQueue, {
      batchSize: 10,
    }));
    urlsParameter.grantRead(dispatcherLambda);
    checksBus.grantPutEventsTo(dispatcherLambda);

    // Lambda that makes the network requests and records results
    const checkerLambda = new lambda.Function(this, 'CheckerererLambda', {
      functionName: isLocalstack ? 'checkererer-localstack' : 'checkererer',
      runtime: lambda.Runtime.PROVIDED_AL2023,
      architecture: lambda.Architecture.X86_64,
      handler: 'bootstrap',
      code: lambda.Code.fromAsset(path.join(__dirname, '../lambda/checkererer')),
      timeout: cdk.Duration.seconds(30),
      memorySize: 128,
      environment: {
        DDB_TABLE: uptimeTable.tableName,
        SNS_TOPIC_ARN: alertsTopic.topicArn,
        ...(isLocalstack && { AWS_ENDPOINT: localstackEndpoint }),
      },
      logGroup: new logs.LogGroup(this, 'CheckerererLogGroup', {
        logGroupName: isLocalstack
          ? '/aws/lambda/checkererer-localstack'
          : '/aws/lambda/checkererer',
        retention: logRetention,
      }),
    });

    // Route pending check requests from the bus to the checker
    new events.Rule(this, 'CheckRequestedRule', {
      ruleName: isLocalstack ? 'uptimererer-check-requested' : undefined,
      description: 'Routes check.requested events to the checkererer Lambda',
      eventBus: checksBus,
      eventPattern: {
        source: ['uptimererer.dispatchererer'],
        detailType: ['check.requested'],
      },
      targets: [new targets.LambdaFunction(checkerLambda, { retryAttempts: 2 })],
    });

    uptimeTable.grantReadWriteData(checkerLambda);
    alertsTopic.grantPublish(checkerLambda);

    // Outputs
    new cdk.CfnOutput(this, 'DispatcherLambdaFunctionArn', {
      value: dispatcherLambda.functionArn,
      description: 'ARN of the dispatchererer Lambda function',
    });

    new cdk.CfnOutput(this, 'LambdaFunctionArn', {
      value: checkerLambda.functionArn,
      description: 'ARN of the checkererer Lambda function',
    });

    new cdk.CfnOutput(this, 'DynamoDBTableName', {
      value: uptimeTable.tableName,
      description: 'Name of the DynamoDB table for uptime checks',
    });

    new cdk.CfnOutput(this, 'TicksQueueUrl', {
      value: ticksQueue.queueUrl,
      description: 'URL of the SQS queue carrying scheduler ticks',
    });

    new cdk.CfnOutput(this, 'ChecksBusName', {
      value: checksBus.eventBusName,
      description: 'Name of the EventBridge bus carrying check requests',
    });

    new cdk.CfnOutput(this, 'AlertsTopicArn', {
      value: alertsTopic.topicArn,
      description: 'ARN of the SNS topic for offline/recovery alerts',
    });

    new cdk.CfnOutput(this, 'UrlConfigsParameterName', {
      value: urlsParameter.parameterName,
      description: 'SSM parameter holding the tracked-URL configs',
    });

    nag.NagSuppressions.addStackSuppressions(this, [
      {
        id: 'AwsSolutions-IAM4',
        reason: 'Lambda functions use the AWS managed basic execution role policy',
      },
      {
        id: 'AwsSolutions-IAM5',
        reason: 'Grants are scoped to the uptime table, alerts topic, URL parameter and checks bus',
      },
    ]);
  }
}
