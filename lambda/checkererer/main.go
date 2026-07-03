// Command checkererer is the "makes network requests" Lambda from the
// architecture diagram. It consumes check.requested events from the custom
// EventBridge bus, performs an HTTP check against the requested URL, stores
// the result and the site's current state in DynamoDB, and publishes to SNS
// when a site goes offline or recovers.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"time"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	ddbtypes "github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/aws/aws-sdk-go-v2/service/sns"
)

const (
	stateUp   = "UP"
	stateDown = "DOWN"

	// stateSortKey is the sort key of the single per-URL item holding the
	// site's current state; history items use an RFC3339 timestamp instead.
	stateSortKey = "STATE"
)

type checkRequest struct {
	URL string `json:"url"`
}

type checkResult struct {
	URL       string
	CheckedAt time.Time
	Status    int
	Latency   time.Duration
	Err       error
}

func (r checkResult) state() string {
	if r.Err != nil || r.Status >= 400 {
		return stateDown
	}
	return stateUp
}

type siteState struct {
	State     string
	ChangedAt string
}

type app struct {
	ddb            *dynamodb.Client
	sns            *sns.Client
	httpClient     *http.Client
	table          string
	topicARN       string
	requestTimeout time.Duration
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC | log.Lshortfile)

	ctx := context.Background()
	cfg, err := config.LoadDefaultConfig(ctx)
	if err != nil {
		log.Fatalf("load aws config: %v", err)
	}
	if endpoint := os.Getenv("AWS_ENDPOINT"); endpoint != "" {
		cfg.BaseEndpoint = aws.String(endpoint)
	}

	requestTimeout := envOrDefaultDuration("REQUEST_TIMEOUT", 10*time.Second)

	a := &app{
		ddb:            dynamodb.NewFromConfig(cfg),
		sns:            sns.NewFromConfig(cfg),
		httpClient:     &http.Client{Timeout: requestTimeout},
		table:          envOrDefault("DDB_TABLE", "uptime_checks"),
		topicARN:       os.Getenv("SNS_TOPIC_ARN"),
		requestTimeout: requestTimeout,
	}

	lambda.Start(a.handle)
}

func (a *app) handle(ctx context.Context, event events.CloudWatchEvent) error {
	var req checkRequest
	if err := json.Unmarshal(event.Detail, &req); err != nil {
		return fmt.Errorf("unmarshal event detail: %w", err)
	}
	if req.URL == "" {
		return errors.New("event detail is missing \"url\"")
	}
	if _, err := url.ParseRequestURI(req.URL); err != nil {
		return fmt.Errorf("invalid url %q: %w", req.URL, err)
	}

	result := a.doCheck(ctx, req.URL)
	newState := result.state()

	prev, err := a.getState(ctx, req.URL)
	if err != nil {
		return fmt.Errorf("get previous state: %w", err)
	}

	if err := a.putHistory(ctx, result); err != nil {
		return fmt.Errorf("store check result: %w", err)
	}
	if err := a.putState(ctx, result, prev); err != nil {
		return fmt.Errorf("store site state: %w", err)
	}

	// Notify on every transition, and on the very first check if the site is
	// already down.
	transitioned := prev != nil && prev.State != newState
	firstSeenDown := prev == nil && newState == stateDown
	if transitioned || firstSeenDown {
		if err := a.notify(ctx, result); err != nil {
			return fmt.Errorf("publish notification: %w", err)
		}
	}

	log.Printf("check done: url=%s state=%s status=%d latency_ms=%d notified=%t",
		result.URL, newState, result.Status, result.Latency.Milliseconds(), transitioned || firstSeenDown)
	return nil
}

func (a *app) doCheck(ctx context.Context, target string) checkResult {
	result := checkResult{URL: target, CheckedAt: time.Now().UTC()}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		result.Err = fmt.Errorf("build request: %w", err)
		return result
	}

	start := time.Now()
	resp, err := a.httpClient.Do(req)
	result.Latency = time.Since(start)
	if err != nil {
		result.Err = fmt.Errorf("http request: %w", err)
		return result
	}
	defer func() { _ = resp.Body.Close() }()

	result.Status = resp.StatusCode
	return result
}

func (a *app) getState(ctx context.Context, target string) (*siteState, error) {
	out, err := a.ddb.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: &a.table,
		Key: map[string]ddbtypes.AttributeValue{
			"pk": &ddbtypes.AttributeValueMemberS{Value: target},
			"sk": &ddbtypes.AttributeValueMemberS{Value: stateSortKey},
		},
		ConsistentRead: aws.Bool(true),
	})
	if err != nil {
		return nil, err
	}
	if out.Item == nil {
		return nil, nil
	}

	state := &siteState{}
	if v, ok := out.Item["state"].(*ddbtypes.AttributeValueMemberS); ok {
		state.State = v.Value
	}
	if v, ok := out.Item["changed_at"].(*ddbtypes.AttributeValueMemberS); ok {
		state.ChangedAt = v.Value
	}
	if state.State == "" {
		return nil, nil
	}
	return state, nil
}

func (a *app) putHistory(ctx context.Context, result checkResult) error {
	checkedAt := result.CheckedAt.Format(time.RFC3339Nano)
	item := map[string]ddbtypes.AttributeValue{
		"pk":         &ddbtypes.AttributeValueMemberS{Value: result.URL},
		"sk":         &ddbtypes.AttributeValueMemberS{Value: checkedAt},
		"url":        &ddbtypes.AttributeValueMemberS{Value: result.URL},
		"checked_at": &ddbtypes.AttributeValueMemberS{Value: checkedAt},
		"status":     &ddbtypes.AttributeValueMemberN{Value: strconv.Itoa(result.Status)},
		"latencyMs":  &ddbtypes.AttributeValueMemberN{Value: strconv.FormatInt(result.Latency.Milliseconds(), 10)},
	}
	if result.Err != nil {
		item["error"] = &ddbtypes.AttributeValueMemberS{Value: result.Err.Error()}
	}

	_, err := a.ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: &a.table, Item: item})
	return err
}

func (a *app) putState(ctx context.Context, result checkResult, prev *siteState) error {
	checkedAt := result.CheckedAt.Format(time.RFC3339Nano)
	newState := result.state()

	changedAt := checkedAt
	if prev != nil && prev.State == newState && prev.ChangedAt != "" {
		changedAt = prev.ChangedAt
	}

	item := map[string]ddbtypes.AttributeValue{
		"pk":         &ddbtypes.AttributeValueMemberS{Value: result.URL},
		"sk":         &ddbtypes.AttributeValueMemberS{Value: stateSortKey},
		"url":        &ddbtypes.AttributeValueMemberS{Value: result.URL},
		"state":      &ddbtypes.AttributeValueMemberS{Value: newState},
		"checked_at": &ddbtypes.AttributeValueMemberS{Value: checkedAt},
		"changed_at": &ddbtypes.AttributeValueMemberS{Value: changedAt},
		"status":     &ddbtypes.AttributeValueMemberN{Value: strconv.Itoa(result.Status)},
	}
	if result.Err != nil {
		item["error"] = &ddbtypes.AttributeValueMemberS{Value: result.Err.Error()}
	}

	_, err := a.ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: &a.table, Item: item})
	return err
}

func (a *app) notify(ctx context.Context, result checkResult) error {
	if a.topicARN == "" {
		log.Printf("SNS_TOPIC_ARN not set; skipping notification for %s", result.URL)
		return nil
	}

	newState := result.state()
	verb := "is back ONLINE"
	if newState == stateDown {
		verb = "has gone OFFLINE"
	}

	message := fmt.Sprintf("%s %s\n\nchecked at: %s\nhttp status: %d\nlatency: %dms\n",
		result.URL, verb, result.CheckedAt.Format(time.RFC3339), result.Status, result.Latency.Milliseconds())
	if result.Err != nil {
		message += fmt.Sprintf("error: %v\n", result.Err)
	}

	subject := fmt.Sprintf("uptimererer: %s %s", result.URL, verb)
	if len(subject) > 100 {
		subject = subject[:100]
	}

	_, err := a.sns.Publish(ctx, &sns.PublishInput{
		TopicArn: &a.topicARN,
		Subject:  &subject,
		Message:  &message,
	})
	return err
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envOrDefaultDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
		log.Printf("invalid duration in %s=%q; using default %s", key, v, def)
	}
	return def
}
