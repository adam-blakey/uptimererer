APP_NAME = uptimererer
STACK_NAME = uptimererer-stack
REGION = us-east-1

# CDK deployment commands
install-cdk:
	npm install

build-lambda:
	cd lambda/checkererer && GOOS=linux GOARCH=amd64 go build -o main main.go

deploy-localstack: install-cdk build-lambda
	npm run deploy-localstack

deploy-aws: install-cdk build-lambda
	npm run deploy-aws

destroy-localstack: install-cdk
	npm run destroy-localstack

destroy-aws: install-cdk
	npm run destroy-aws

diff-localstack: install-cdk
	npx cdk diff --context environment=localstack

diff-aws: install-cdk
	npx cdk diff --context environment=aws

synth-localstack: install-cdk
	npx cdk synth --context environment=localstack

synth-aws: install-cdk
	npx cdk synth --context environment=aws
