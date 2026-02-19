import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as nag from 'cdk-nag';
import * as path from 'path';

export interface UptimerererStackProps extends cdk.StackProps {
  environment: 'localstack' | 'aws';
}

export class UptimerererStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: UptimerererStackProps) {
    super(scope, id, props);

    // DynamoDB table for storing uptime check results
    const uptimeTable = new dynamodb.Table(this, 'UptimeChecksTable', {
      tableName: props.environment === 'localstack' ? 'uptime_checks' : undefined,
      partitionKey: {
        name: 'pk',
        type: dynamodb.AttributeType.STRING,
      },
      sortKey: {
        name: 'sk',
        type: dynamodb.AttributeType.STRING,
      },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: props.environment === 'localstack' 
        ? cdk.RemovalPolicy.DESTROY 
        : cdk.RemovalPolicy.RETAIN,
    });

    // Lambda function for URL checking
    const checkerLambda = new lambda.Function(this, 'CheckerererLambda', {
      functionName: props.environment === 'localstack' 
        ? 'checkererer-localstack' 
        : 'checkererer',
      runtime: lambda.Runtime.GO_1_X,
      handler: 'main',
      code: lambda.Code.fromAsset(path.join(__dirname, '../lambda/checkererer')),
      timeout: cdk.Duration.seconds(30),
      memorySize: 128,
      environment: {
        DDB_TABLE: uptimeTable.tableName,
        ...(props.environment === 'localstack' && {
          AWS_ENDPOINT: 'http://localhost:4566',
        }),
      },
      logGroup: new logs.LogGroup(this, 'CheckerererLogGroup', {
        logGroupName: props.environment === 'localstack' 
          ? '/aws/lambda/checkererer-localstack' 
          : '/aws/lambda/checkererer',
        retention: props.environment === 'localstack' 
          ? logs.RetentionDays.ONE_DAY 
          : logs.RetentionDays.ONE_MONTH,
      }),
    });

    // Grant Lambda permissions to write to DynamoDB
    uptimeTable.grantWriteData(checkerLambda);

    // Add additional IAM permissions for the Lambda
    checkerLambda.addToRolePolicy(new iam.PolicyStatement({
      effect: iam.Effect.ALLOW,
      actions: [
        'dynamodb:PutItem',
        'dynamodb:UpdateItem',
        'dynamodb:GetItem',
        'dynamodb:Query',
        'dynamodb:Scan',
      ],
      resources: [uptimeTable.tableArn],
    }));

    // Output the Lambda function ARN and table name
    new cdk.CfnOutput(this, 'LambdaFunctionArn', {
      value: checkerLambda.functionArn,
      description: 'ARN of the checkererer Lambda function',
    });

    new cdk.CfnOutput(this, 'DynamoDBTableName', {
      value: uptimeTable.tableName,
      description: 'Name of the DynamoDB table for uptime checks',
    });

    nag.NagSuppressions.addStackSuppressions(this, [
      {
        id: 'AwsSolutions-IAM4',
        reason: 'Lambda function needs DynamoDB permissions to write check results',
      },
      {
        id: 'AwsSolutions-IAM5',
        reason: 'Lambda function needs specific DynamoDB actions for the uptime table',
      },
    ]);
  }
}
