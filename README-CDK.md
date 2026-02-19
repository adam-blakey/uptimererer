# Uptimererer CDK Deployment

This project uses AWS CDK to deploy the checkererer Lambda function and DynamoDB table to either LocalStack or AWS.

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

1. **DynamoDB Table**: Stores uptime check results with:
   - Partition key: `pk` (URL)
   - Sort key: `sk` (timestamp)
   - Pay-per-request billing

2. **Lambda Function**: The checkererer function with:
   - Go 1.x runtime
   - 30-second timeout
   - 128MB memory
   - Write permissions to DynamoDB table
   - Environment variables for table name and AWS endpoint

## Environment Configuration

- **LocalStack**: Uses `http://localhost:4566` as AWS endpoint
- **AWS**: Uses standard AWS endpoints
- Table names and function names are prefixed based on environment

## Testing the Lambda

After deployment, you can test the Lambda function:

```bash
# LocalStack
aws lambda invoke \
  --function-name checkererer-localstack \
  --endpoint-url http://localhost:4566 \
  --payload '{"url": "https://example.com"}' \
  response.json

# AWS
aws lambda invoke \
  --function-name checkererer \
  --payload '{"url": "https://example.com"}' \
  response.json
```

## Environment Variables

The Lambda function is configured with:

- `DDB_TABLE`: Name of the DynamoDB table
- `AWS_REGION`: AWS region
- `AWS_ENDPOINT`: LocalStack endpoint (only for LocalStack deployment)
