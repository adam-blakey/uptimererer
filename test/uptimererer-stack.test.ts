import * as cdk from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { UptimerererStack } from '../lib/uptimererer-stack';

function synth(environment: 'localstack' | 'aws'): Template {
  const app = new cdk.App();
  const stack = new UptimerererStack(app, 'TestStack', {
    env: { account: '000000000000', region: 'us-east-1' },
    environment,
  });
  return Template.fromStack(stack);
}

describe('UptimerererStack', () => {
  const template = synth('localstack');

  test('creates the uptime checks DynamoDB table', () => {
    template.hasResourceProperties('AWS::DynamoDB::Table', {
      KeySchema: [
        { AttributeName: 'pk', KeyType: 'HASH' },
        { AttributeName: 'sk', KeyType: 'RANGE' },
      ],
      BillingMode: 'PAY_PER_REQUEST',
    });
  });

  test('creates the URL configs SSM parameter', () => {
    template.hasResourceProperties('AWS::SSM::Parameter', {
      Name: '/uptimererer/urls',
      Value: JSON.stringify(['https://example.com']),
    });
  });

  test('schedules a tick onto the SQS queue every minute', () => {
    template.hasResourceProperties('AWS::Events::Rule', {
      ScheduleExpression: 'rate(1 minute)',
      Targets: [
        Match.objectLike({
          Arn: Match.objectLike({ 'Fn::GetAtt': [Match.stringLikeRegexp('TicksQueue'), 'Arn'] }),
        }),
      ],
    });
  });

  test('creates the ticks queue with a dead-letter queue', () => {
    template.hasResourceProperties('AWS::SQS::Queue', {
      QueueName: 'uptimererer-ticks',
      RedrivePolicy: Match.objectLike({ maxReceiveCount: 3 }),
    });
  });

  test('wires the dispatcher Lambda to the ticks queue', () => {
    template.hasResourceProperties('AWS::Lambda::Function', {
      FunctionName: 'dispatchererer-localstack',
      Runtime: 'provided.al2023',
      Handler: 'bootstrap',
      Environment: {
        Variables: Match.objectLike({
          URLS_PARAMETER: Match.anyValue(),
          EVENT_BUS_NAME: Match.anyValue(),
        }),
      },
    });
    template.hasResourceProperties('AWS::Lambda::EventSourceMapping', {
      FunctionName: Match.objectLike({ Ref: Match.stringLikeRegexp('DispatcherererLambda') }),
    });
  });

  test('creates the custom checks bus and routes check.requested to the checker', () => {
    template.hasResourceProperties('AWS::Events::EventBus', {
      Name: 'uptimererer-bus',
    });
    template.hasResourceProperties('AWS::Events::Rule', {
      EventPattern: {
        source: ['uptimererer.dispatchererer'],
        'detail-type': ['check.requested'],
      },
      Targets: [
        Match.objectLike({
          Arn: Match.objectLike({ 'Fn::GetAtt': [Match.stringLikeRegexp('CheckerererLambda'), 'Arn'] }),
        }),
      ],
    });
  });

  test('configures the checker Lambda with table and topic', () => {
    template.hasResourceProperties('AWS::Lambda::Function', {
      FunctionName: 'checkererer-localstack',
      Runtime: 'provided.al2023',
      Handler: 'bootstrap',
      Environment: {
        Variables: Match.objectLike({
          DDB_TABLE: Match.anyValue(),
          SNS_TOPIC_ARN: Match.anyValue(),
        }),
      },
    });
  });

  test('creates the alerts SNS topic', () => {
    template.hasResourceProperties('AWS::SNS::Topic', {
      TopicName: 'uptimererer-alerts',
    });
  });

  test('grants the checker publish access to the alerts topic', () => {
    template.hasResourceProperties('AWS::IAM::Policy', {
      PolicyDocument: Match.objectLike({
        Statement: Match.arrayWith([
          Match.objectLike({
            Action: 'sns:Publish',
            Resource: Match.objectLike({ Ref: Match.stringLikeRegexp('AlertsTopic') }),
          }),
        ]),
      }),
    });
  });

  test('subscribes an alert email when the alertEmail context is set', () => {
    const app = new cdk.App({ context: { alertEmail: 'someone@example.com' } });
    const stack = new UptimerererStack(app, 'TestStackWithEmail', {
      env: { account: '000000000000', region: 'us-east-1' },
      environment: 'aws',
    });
    Template.fromStack(stack).hasResourceProperties('AWS::SNS::Subscription', {
      Protocol: 'email',
      Endpoint: 'someone@example.com',
    });
  });
});
