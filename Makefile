APP_NAME = uptimererer
STACK_NAME = uptimererer-stack
REGION = us-east-1

# CDK deployment commands
install-cdk:
	npm install

build-lambda: build-checkererer build-dispatchererer

build-checkererer:
	cd lambda/checkererer && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o bootstrap .

build-dispatchererer:
	cd lambda/dispatchererer && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o bootstrap .

test: install-cdk
	cd lambda/checkererer && go vet ./... && go test ./...
	cd lambda/dispatchererer && go vet ./... && go test ./...
	npm test

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
