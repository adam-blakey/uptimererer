package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

type checkItem struct {
	URL       string
	CheckedAt string
	Status    int32
	LatencyMS int64
	Error     string
}

func main() {
	// Config and validation.
	log.SetFlags(log.LstdFlags | log.LUTC | log.Lshortfile)

	targetURL := flag.String("url", "", "URL to hit")
	table := flag.String("table", envOrDefault("DDB_TABLE", "uptime_checks"), "DynamoDB table name")
	requestTimeout := flag.Duration("request-timeout", envOrDefaultDuration("REQUEST_TIMEOUT", 10*time.Second), "HTTP request timeout")
	runTimeout := flag.Duration("run-timeout", envOrDefaultDuration("RUN_TIMEOUT", 12*time.Second), "Program run timeout")
	flag.Parse()

	if *targetURL == "" {
		log.Fatal("missing required -url")
	}
	if _, err := url.ParseRequestURI(*targetURL); err != nil {
		log.Fatalf("invalid -url: %v", err)
	}
	if *requestTimeout > *runTimeout {
		log.Fatal("Request timeout must be less than run timeout")
	}

	ctx, cancel := context.WithTimeout(context.Background(), *runTimeout)
	defer cancel()

	// Make URL check.
	status, latency, checkErr := doCheck(ctx, *targetURL, *requestTimeout)

	// Save response.
	ddb, err := newDynamoClient(ctx)
	if err != nil {
		log.Fatalf("dynamodb client: %v", err)
	}

	item := checkItem{
		URL:       *targetURL,
		CheckedAt: time.Now().UTC().Format(time.RFC3339Nano),
		Status:    status,
		LatencyMS: latency.Milliseconds(),
	}
	if checkErr != nil {
		item.Error = checkErr.Error()
	}

	if err := putCheck(ctx, ddb, *table, item); err != nil {
		log.Fatalf("dynamodb put item: %v", err)
	}

	if checkErr != nil {
		log.Printf("check failed: url=%s err=%v (stored in dynamodb)", *targetURL, checkErr)
		return
	}

	log.Printf("check ok: url=%s status=%d latency_ms=%d (stored in dynamodb)", *targetURL, status, item.LatencyMS)
}

func doCheck(ctx context.Context, target string, requestTimeout time.Duration) (status int32, latency time.Duration, err error) {
	// Create an HTTP client.
	client := &http.Client{Timeout: requestTimeout}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return 0, 0, fmt.Errorf("build request: %w", err)
	}

	// Make request.
	start := time.Now()
	resp, err := client.Do(req)
	latency = time.Since(start)
	if err != nil {
		return 0, latency, fmt.Errorf("http request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	return int32(resp.StatusCode), latency, nil
}

func newDynamoClient(ctx context.Context) (*dynamodb.Client, error) {
	region := envOrDefault("AWS_REGION", "us-east-1")
	endpoint := envOrDefault("AWS_ENDPOINT", "http://localhost:4566")

	cfg, err := config.LoadDefaultConfig(ctx, config.WithRegion(region))
	if err != nil {
		return nil, err
	}

	if endpoint != "" {
		cfg.BaseEndpoint = aws.String(endpoint)
	}

	return dynamodb.NewFromConfig(cfg), nil
}

func putCheck(ctx context.Context, ddb *dynamodb.Client, table string, item checkItem) error {
	av := map[string]types.AttributeValue{
		"pk":         &types.AttributeValueMemberS{Value: item.URL},
		"sk":         &types.AttributeValueMemberS{Value: item.CheckedAt},
		"url":        &types.AttributeValueMemberS{Value: item.URL},
		"checked_at": &types.AttributeValueMemberS{Value: item.CheckedAt},
		"status":     &types.AttributeValueMemberN{Value: strconv.FormatInt(int64(item.Status), 10)},
		"latencyMs":  &types.AttributeValueMemberN{Value: strconv.FormatInt(item.LatencyMS, 10)},
	}

	if item.Error != "" {
		av["error"] = &types.AttributeValueMemberS{Value: item.Error}
	}

	_, err := ddb.PutItem(ctx, &dynamodb.PutItemInput{
		TableName: &table,
		Item:      av,
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
		i := int(def)
		t := time.Duration(i)
		return t
	}
	return def
}
