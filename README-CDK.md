# Uptimererer CDK Deployment

This project uses AWS CDK to deploy the full uptimererer pipeline (see
`docs/architecture-diagram.png`) to either LocalStack or AWS.

## Prerequisites

- Node.js (v16 or later)
- AWS CLI configured (for AWS deployment)
- Docker (for building Lambda)
- LocalStack (for local deployment)

## Installation

```bash
# Install CDK dependencies
make install-cdk

# Or manually
npm install
```

## Deployment

### LocalStack Deployment

```bash
# Deploy to LocalStack
make deploy-localstack

# Or manually
npm run deploy-localstack
```

### AWS Deployment

```bash
# Deploy to AWS
make deploy-aws

# Or manually
npm run deploy-aws
```

## Other Commands

### Destroy Resources

```bash
# Destroy LocalStack resources
make destroy-localstack

# Destroy AWS resources
make destroy-aws
```

### View Changes

```bash
# See what will change in LocalStack
make diff-localstack

# See what will change in AWS
make diff-aws
```

### Synthesize CloudFormation

```bash
# Generate CloudFormation for LocalStack
make synth-localstack

# Generate CloudFormation for AWS
make synth-aws
```

## Architecture

The CDK stack creates:

1. **EventBridge schedule rule**: Fires every minute, targeting the ticks SQS
   queue.

2. **SQS queues**: `uptimererer-ticks` (scheduler ticks; triggers the
   dispatcher) plus a dead-letter queue.

3. **Dispatchererer Lambda**: Reads the URL configs from SSM and puts one
   `check.requested` event per URL onto the custom EventBridge bus.

4. **SSM Parameter** (`/uptimererer/urls`): JSON string array of tracked URLs,
   e.g. `["https://example.com"]`. Edit it to change what gets monitored
   (note: a redeploy resets it to the seeded value).

5. **Custom EventBridge bus** (`uptimererer-bus`): Carries pending
   `check.requested` events; a rule routes them to the checker.

6. **Checkererer Lambda**: Makes the HTTP request and records the result.
   Both Lambdas are Go binaries on the `provided.al2023` runtime with a
   30-second timeout and 128MB memory.

7. **DynamoDB Table**: Stores uptime check history (partition key `pk` = URL,
   sort key `sk` = timestamp) and one current-state item per URL
   (`sk` = `STATE`). Pay-per-request billing.

8. **SNS Topic**: Receives a message whenever a site goes offline or recovers.
   Pass `--context alertEmail=you@example.com` at deploy time to subscribe an
   email address.

## Environment Configuration

- **LocalStack**: Uses `http://localhost:4566` as AWS endpoint
- **AWS**: Uses standard AWS endpoints
- Table names and function names are prefixed based on environment

## Testing the pipeline

After deployment the schedule drives everything: within a minute the
dispatcher reads `/uptimererer/urls` and the checker starts writing results.
To exercise the checker directly, send it the EventBridge event shape:

```bash
# LocalStack
aws lambda invoke \
  --function-name checkererer-localstack \
  --endpoint-url http://localhost:4566 \
  --payload '{"detail-type": "check.requested", "source": "uptimererer.dispatchererer", "detail": {"url": "https://example.com"}}' \
  response.json

# AWS
aws lambda invoke \
  --function-name checkererer \
  --payload '{"detail-type": "check.requested", "source": "uptimererer.dispatchererer", "detail": {"url": "https://example.com"}}' \
  response.json
```

Change the tracked URLs:

```bash
aws ssm put-parameter --name /uptimererer/urls --overwrite \
  --value '["https://example.com", "https://adamblakey.com"]'
```

## Environment Variables

The checkererer Lambda is configured with:

- `DDB_TABLE`: Name of the DynamoDB table
- `SNS_TOPIC_ARN`: Topic to notify on offline/recovery transitions
- `REQUEST_TIMEOUT`: HTTP request timeout (Go duration, default `10s`)
- `AWS_ENDPOINT`: LocalStack endpoint (only for LocalStack deployment)

The dispatchererer Lambda is configured with:

- `URLS_PARAMETER`: Name of the SSM parameter holding the URL configs
- `EVENT_BUS_NAME`: Name of the custom EventBridge bus
- `AWS_ENDPOINT`: LocalStack endpoint (only for LocalStack deployment)
