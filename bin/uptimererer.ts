#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { UptimerererStack } from '../lib/uptimererer-stack';

const app = new cdk.App();

// Get environment context (localstack or aws)
const environment = app.node.tryGetContext('environment') || 'aws';

// Set stack name based on environment
const stackName = environment === 'localstack' 
  ? 'UptimerererStack-LocalStack' 
  : 'UptimerererStack';

new UptimerererStack(app, stackName, {
  env: {
    region: process.env.CDK_DEFAULT_REGION || 'us-east-1',
    account: environment === 'localstack' ? '000000000000' : process.env.CDK_DEFAULT_ACCOUNT,
  },
  environment,
  description: `Uptimererer stack for ${environment}`,
});
