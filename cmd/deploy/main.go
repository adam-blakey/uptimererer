// Command deploy provisions the checker stack — DynamoDB table, EventBridge
// bus + rule, and the checker Lambda — against any AWS-compatible endpoint.
// It exists so the stack can be stood up on the local Floci emulator with no
// extra tooling: point AWS_ENDPOINT_URL at the emulator (the Makefile does
// this) and run the subcommands.
//
//	deploy                    provision/update everything from dist/checker/bootstrap
//	send -url URL [-id ID] [-failures N]
//	                          put a CheckRequested event on the bus
//	state                     print all site state records from DynamoDB
package main

import (
	"archive/zip"
	"bytes"
	"context"
	"debug/elf"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/url"
	"os"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	ddbtypes "github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/aws/aws-sdk-go-v2/service/eventbridge"
	ebtypes "github.com/aws/aws-sdk-go-v2/service/eventbridge/types"
	"github.com/aws/aws-sdk-go-v2/service/iam"
	iamtypes "github.com/aws/aws-sdk-go-v2/service/iam/types"
	lambdasvc "github.com/aws/aws-sdk-go-v2/service/lambda"
	lambdatypes "github.com/aws/aws-sdk-go-v2/service/lambda/types"

	"github.com/adamblakey/uptimererer/internal/events"
	"github.com/adamblakey/uptimererer/internal/state"
)

const (
	tableName    = "uptimererer-state"
	busName      = "uptimererer-bus"
	ruleName     = "uptimererer-check-requested"
	functionName = "uptimererer-checker"
	roleName     = "uptimererer-checker-role"

	bootstrapPath = "dist/checker/bootstrap"
	readyTimeout  = 90 * time.Second
)

type clients struct {
	ddb    *dynamodb.Client
	eb     *eventbridge.Client
	iam    *iam.Client
	lambda *lambdasvc.Client
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)

	if len(os.Args) < 2 {
		log.Fatalf("usage: deploy <deploy|send|state> [flags]")
	}

	ctx := context.Background()
	cfg, err := config.LoadDefaultConfig(ctx)
	if err != nil {
		log.Fatalf("load aws config: %v", err)
	}
	c := clients{
		ddb:    dynamodb.NewFromConfig(cfg),
		eb:     eventbridge.NewFromConfig(cfg),
		iam:    iam.NewFromConfig(cfg),
		lambda: lambdasvc.NewFromConfig(cfg),
	}

	switch os.Args[1] {
	case "deploy":
		err = runDeploy(ctx, c)
	case "send":
		err = runSend(ctx, c, os.Args[2:])
	case "state":
		err = runState(ctx, c)
	default:
		err = fmt.Errorf("unknown subcommand %q (expected deploy, send or state)", os.Args[1])
	}
	if err != nil {
		log.Fatal(err)
	}
}

func runDeploy(ctx context.Context, c clients) error {
	if err := waitReady(ctx, c.ddb); err != nil {
		return err
	}

	roleARN, err := ensureRole(ctx, c.iam)
	if err != nil {
		return err
	}
	if err := ensureTable(ctx, c.ddb); err != nil {
		return err
	}
	fnARN, err := ensureFunction(ctx, c.lambda, roleARN)
	if err != nil {
		return err
	}
	if err := ensureBusAndRule(ctx, c, fnARN); err != nil {
		return err
	}

	log.Printf("deployed: table=%s bus=%s function=%s", tableName, busName, functionName)
	log.Printf("try: make check URL=https://example.com")
	return nil
}

// waitReady polls until the endpoint answers DynamoDB calls, so `deploy` can
// run immediately after `docker compose up -d`.
func waitReady(ctx context.Context, ddb *dynamodb.Client) error {
	deadline := time.Now().Add(readyTimeout)
	for {
		callCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
		_, err := ddb.ListTables(callCtx, &dynamodb.ListTablesInput{})
		cancel()
		if err == nil {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("endpoint not ready after %s (is the emulator running? make local-up): %w", readyTimeout, err)
		}
		time.Sleep(time.Second)
	}
}

func ensureRole(ctx context.Context, c *iam.Client) (string, error) {
	const trust = `{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}`

	out, err := c.CreateRole(ctx, &iam.CreateRoleInput{
		RoleName:                 aws.String(roleName),
		AssumeRolePolicyDocument: aws.String(trust),
	})
	if err == nil {
		log.Printf("created role %s", roleName)
		return *out.Role.Arn, nil
	}
	var exists *iamtypes.EntityAlreadyExistsException
	if !errors.As(err, &exists) {
		return "", fmt.Errorf("create role: %w", err)
	}
	got, err := c.GetRole(ctx, &iam.GetRoleInput{RoleName: aws.String(roleName)})
	if err != nil {
		return "", fmt.Errorf("get role: %w", err)
	}
	return *got.Role.Arn, nil
}

func ensureTable(ctx context.Context, c *dynamodb.Client) error {
	_, err := c.CreateTable(ctx, &dynamodb.CreateTableInput{
		TableName:   aws.String(tableName),
		BillingMode: ddbtypes.BillingModePayPerRequest,
		AttributeDefinitions: []ddbtypes.AttributeDefinition{
			{AttributeName: aws.String("siteId"), AttributeType: ddbtypes.ScalarAttributeTypeS},
		},
		KeySchema: []ddbtypes.KeySchemaElement{
			{AttributeName: aws.String("siteId"), KeyType: ddbtypes.KeyTypeHash},
		},
	})
	var inUse *ddbtypes.ResourceInUseException
	if err != nil && !errors.As(err, &inUse) {
		return fmt.Errorf("create table: %w", err)
	}
	if err == nil {
		log.Printf("created table %s", tableName)
	}

	waiter := dynamodb.NewTableExistsWaiter(c)
	if err := waiter.Wait(ctx, &dynamodb.DescribeTableInput{TableName: aws.String(tableName)}, time.Minute); err != nil {
		return fmt.Errorf("waiting for table %s: %w", tableName, err)
	}
	return nil
}

func ensureFunction(ctx context.Context, c *lambdasvc.Client, roleARN string) (string, error) {
	code, arch, err := zipBootstrap()
	if err != nil {
		return "", err
	}

	env := &lambdatypes.Environment{Variables: map[string]string{"STATE_TABLE": tableName}}

	_, err = c.GetFunction(ctx, &lambdasvc.GetFunctionInput{FunctionName: aws.String(functionName)})
	var notFound *lambdatypes.ResourceNotFoundException
	switch {
	case errors.As(err, &notFound):
		out, err := c.CreateFunction(ctx, &lambdasvc.CreateFunctionInput{
			FunctionName:  aws.String(functionName),
			Role:          aws.String(roleARN),
			Runtime:       lambdatypes.RuntimeProvidedal2023,
			Handler:       aws.String("bootstrap"),
			Architectures: []lambdatypes.Architecture{arch},
			Code:          &lambdatypes.FunctionCode{ZipFile: code},
			Environment:   env,
			Timeout:       aws.Int32(30),
			MemorySize:    aws.Int32(128),
		})
		if err != nil {
			return "", fmt.Errorf("create function: %w", err)
		}
		log.Printf("created function %s (%s)", functionName, arch)
		if err := waitFunctionSettled(ctx, c); err != nil {
			return "", err
		}
		return *out.FunctionArn, nil

	case err != nil:
		return "", fmt.Errorf("get function: %w", err)

	default:
		out, err := c.UpdateFunctionCode(ctx, &lambdasvc.UpdateFunctionCodeInput{
			FunctionName:  aws.String(functionName),
			ZipFile:       code,
			Architectures: []lambdatypes.Architecture{arch},
		})
		if err != nil {
			return "", fmt.Errorf("update function code: %w", err)
		}
		if err := waitFunctionSettled(ctx, c); err != nil {
			return "", err
		}
		if _, err := c.UpdateFunctionConfiguration(ctx, &lambdasvc.UpdateFunctionConfigurationInput{
			FunctionName: aws.String(functionName),
			Role:         aws.String(roleARN),
			Environment:  env,
		}); err != nil {
			return "", fmt.Errorf("update function configuration: %w", err)
		}
		if err := waitFunctionSettled(ctx, c); err != nil {
			return "", err
		}
		log.Printf("updated function %s (%s)", functionName, arch)
		return *out.FunctionArn, nil
	}
}

func waitFunctionSettled(ctx context.Context, c *lambdasvc.Client) error {
	in := &lambdasvc.GetFunctionInput{FunctionName: aws.String(functionName)}
	if err := lambdasvc.NewFunctionActiveV2Waiter(c).Wait(ctx, in, time.Minute); err != nil {
		return fmt.Errorf("waiting for function active: %w", err)
	}
	if err := lambdasvc.NewFunctionUpdatedV2Waiter(c).Wait(ctx, in, time.Minute); err != nil {
		return fmt.Errorf("waiting for function update: %w", err)
	}
	return nil
}

// zipBootstrap packages the compiled handler and derives the Lambda
// architecture from the binary itself so the function config can never
// disagree with what the Makefile built.
func zipBootstrap() ([]byte, lambdatypes.Architecture, error) {
	data, err := os.ReadFile(bootstrapPath)
	if err != nil {
		return nil, "", fmt.Errorf("read %s (run `make build` first): %w", bootstrapPath, err)
	}

	f, err := elf.NewFile(bytes.NewReader(data))
	if err != nil {
		return nil, "", fmt.Errorf("%s is not a linux binary (built with GOOS=linux?): %w", bootstrapPath, err)
	}
	var arch lambdatypes.Architecture
	switch f.Machine {
	case elf.EM_X86_64:
		arch = lambdatypes.ArchitectureX8664
	case elf.EM_AARCH64:
		arch = lambdatypes.ArchitectureArm64
	default:
		return nil, "", fmt.Errorf("%s: unsupported architecture %v", bootstrapPath, f.Machine)
	}

	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	hdr := &zip.FileHeader{Name: "bootstrap", Method: zip.Deflate}
	hdr.SetMode(0o755)
	fw, err := w.CreateHeader(hdr)
	if err != nil {
		return nil, "", err
	}
	if _, err := fw.Write(data); err != nil {
		return nil, "", err
	}
	if err := w.Close(); err != nil {
		return nil, "", err
	}
	return buf.Bytes(), arch, nil
}

func ensureBusAndRule(ctx context.Context, c clients, fnARN string) error {
	_, err := c.eb.CreateEventBus(ctx, &eventbridge.CreateEventBusInput{Name: aws.String(busName)})
	var busExists *ebtypes.ResourceAlreadyExistsException
	if err != nil && !errors.As(err, &busExists) {
		return fmt.Errorf("create event bus: %w", err)
	}
	if err == nil {
		log.Printf("created event bus %s", busName)
	}

	pattern := fmt.Sprintf(`{"source":[%q],"detail-type":[%q]}`, events.Source, events.DetailTypeCheckRequested)
	rule, err := c.eb.PutRule(ctx, &eventbridge.PutRuleInput{
		Name:         aws.String(ruleName),
		EventBusName: aws.String(busName),
		EventPattern: aws.String(pattern),
		State:        ebtypes.RuleStateEnabled,
	})
	if err != nil {
		return fmt.Errorf("put rule: %w", err)
	}

	if _, err := c.eb.PutTargets(ctx, &eventbridge.PutTargetsInput{
		Rule:         aws.String(ruleName),
		EventBusName: aws.String(busName),
		Targets:      []ebtypes.Target{{Id: aws.String("checker"), Arn: aws.String(fnARN)}},
	}); err != nil {
		return fmt.Errorf("put targets: %w", err)
	}

	_, err = c.lambda.AddPermission(ctx, &lambdasvc.AddPermissionInput{
		FunctionName: aws.String(functionName),
		StatementId:  aws.String("uptimererer-eventbridge"),
		Action:       aws.String("lambda:InvokeFunction"),
		Principal:    aws.String("events.amazonaws.com"),
		SourceArn:    rule.RuleArn,
	})
	var conflict *lambdatypes.ResourceConflictException
	if err != nil && !errors.As(err, &conflict) {
		return fmt.Errorf("add invoke permission: %w", err)
	}
	return nil
}

func runSend(ctx context.Context, c clients, args []string) error {
	fs := flag.NewFlagSet("send", flag.ExitOnError)
	siteURL := fs.String("url", "", "URL to check (required)")
	siteID := fs.String("id", "", "site id (defaults to the URL's host)")
	failures := fs.Int("failures", 0, "failuresBeforeDown override (0 = default)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *siteURL == "" {
		return errors.New("send: missing required -url")
	}
	if *siteID == "" {
		u, err := url.Parse(*siteURL)
		if err != nil || u.Host == "" {
			return fmt.Errorf("send: cannot derive -id from url %q", *siteURL)
		}
		*siteID = u.Host
	}

	detail, err := json.Marshal(events.CheckRequested{
		SiteConfig: events.SiteConfig{
			ID:                 *siteID,
			URL:                *siteURL,
			FailuresBeforeDown: *failures,
		},
		RequestedAt: time.Now().UTC(),
	})
	if err != nil {
		return err
	}

	out, err := c.eb.PutEvents(ctx, &eventbridge.PutEventsInput{
		Entries: []ebtypes.PutEventsRequestEntry{{
			EventBusName: aws.String(busName),
			Source:       aws.String(events.Source),
			DetailType:   aws.String(events.DetailTypeCheckRequested),
			Detail:       aws.String(string(detail)),
		}},
	})
	if err != nil {
		return fmt.Errorf("put events: %w", err)
	}
	if out.FailedEntryCount > 0 {
		e := out.Entries[0]
		return fmt.Errorf("put events failed: %s %s", aws.ToString(e.ErrorCode), aws.ToString(e.ErrorMessage))
	}

	log.Printf("sent CheckRequested for %s (%s)", *siteID, *siteURL)
	return nil
}

func runState(ctx context.Context, c clients) error {
	p := dynamodb.NewScanPaginator(c.ddb, &dynamodb.ScanInput{TableName: aws.String(tableName)})
	n := 0
	for p.HasMorePages() {
		page, err := p.NextPage(ctx)
		if err != nil {
			return fmt.Errorf("scan %s: %w", tableName, err)
		}
		var recs []state.Record
		if err := attributevalue.UnmarshalListOfMaps(page.Items, &recs); err != nil {
			return fmt.Errorf("unmarshal records: %w", err)
		}
		for _, rec := range recs {
			line, err := json.Marshal(rec)
			if err != nil {
				return err
			}
			fmt.Println(string(line))
			n++
		}
	}
	if n == 0 {
		log.Printf("no site state yet (send a check first: make check URL=...)")
	}
	return nil
}
