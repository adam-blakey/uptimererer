APP_NAME = s3-workflow
STACK_NAME = s3-workflow-stack
REGION = us-east-1

deploy-localstack:
	go run ./cmd/deploy -target localstack -region $(REGION) -bucket test

deploy-aws:
	go run ./cmd/deploy -target aws -region $(REGION) -bucket my-real-bucket-name

.PHONY: deploy-localstack deploy-aws