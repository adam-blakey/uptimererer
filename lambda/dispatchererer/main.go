// Command dispatchererer is the "decides requests to make" Lambda from the
// architecture diagram. Every minute EventBridge drops a tick message onto an
// SQS queue; this Lambda consumes those ticks, loads the tracked-URL configs
// from Systems Manager Parameter Store, and puts one check.requested event per
// URL onto the custom EventBridge bus for the checkererer Lambda to pick up.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/url"
	"os"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/eventbridge"
	ebtypes "github.com/aws/aws-sdk-go-v2/service/eventbridge/types"
	"github.com/aws/aws-sdk-go-v2/service/ssm"
)

const (
	eventSource     = "uptimererer.dispatchererer"
	eventDetailType = "check.requested"

	// putEventsBatchLimit is the maximum number of entries accepted by a
	// single EventBridge PutEvents call.
	putEventsBatchLimit = 10
)

type app struct {
	ssm           *ssm.Client
	eventBridge   *eventbridge.Client
	urlsParameter string
	eventBusName  string
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

	a := &app{
		ssm:           ssm.NewFromConfig(cfg),
		eventBridge:   eventbridge.NewFromConfig(cfg),
		urlsParameter: envOrDefault("URLS_PARAMETER", "/uptimererer/urls"),
		eventBusName:  envOrDefault("EVENT_BUS_NAME", "default"),
	}

	lambda.Start(a.handle)
}

// handle treats the SQS batch as a single scheduler tick: however many tick
// messages arrived, one round of check.requested events is dispatched. An
// error fails the whole batch so SQS redelivers the ticks.
func (a *app) handle(ctx context.Context, event events.SQSEvent) error {
	if len(event.Records) == 0 {
		return nil
	}

	urls, err := a.loadURLs(ctx)
	if err != nil {
		return fmt.Errorf("load url configs: %w", err)
	}
	if len(urls) == 0 {
		log.Printf("no urls configured in %s; nothing to dispatch", a.urlsParameter)
		return nil
	}

	if err := a.dispatch(ctx, urls); err != nil {
		return err
	}

	log.Printf("dispatched %d check.requested event(s) to bus %s (from %d tick message(s))",
		len(urls), a.eventBusName, len(event.Records))
	return nil
}

// loadURLs reads the SSM parameter holding the tracked-URL configs. The value
// is a JSON array of URL strings, e.g. ["https://example.com"].
func (a *app) loadURLs(ctx context.Context) ([]string, error) {
	out, err := a.ssm.GetParameter(ctx, &ssm.GetParameterInput{
		Name: &a.urlsParameter,
	})
	if err != nil {
		return nil, fmt.Errorf("get parameter %s: %w", a.urlsParameter, err)
	}

	var urls []string
	if err := json.Unmarshal([]byte(*out.Parameter.Value), &urls); err != nil {
		return nil, fmt.Errorf("parse parameter %s as JSON string array: %w", a.urlsParameter, err)
	}

	valid := urls[:0]
	for _, u := range urls {
		if _, err := url.ParseRequestURI(u); err != nil {
			log.Printf("skipping invalid url %q in %s: %v", u, a.urlsParameter, err)
			continue
		}
		valid = append(valid, u)
	}
	return valid, nil
}

func (a *app) dispatch(ctx context.Context, urls []string) error {
	for start := 0; start < len(urls); start += putEventsBatchLimit {
		end := min(start+putEventsBatchLimit, len(urls))

		entries := make([]ebtypes.PutEventsRequestEntry, 0, end-start)
		for _, u := range urls[start:end] {
			detail, err := json.Marshal(map[string]string{"url": u})
			if err != nil {
				return fmt.Errorf("marshal event detail for %s: %w", u, err)
			}
			entries = append(entries, ebtypes.PutEventsRequestEntry{
				Source:       aws.String(eventSource),
				DetailType:   aws.String(eventDetailType),
				Detail:       aws.String(string(detail)),
				EventBusName: &a.eventBusName,
			})
		}

		out, err := a.eventBridge.PutEvents(ctx, &eventbridge.PutEventsInput{Entries: entries})
		if err != nil {
			return fmt.Errorf("put events: %w", err)
		}
		if out.FailedEntryCount > 0 {
			for i, entry := range out.Entries {
				if entry.ErrorCode != nil {
					log.Printf("failed to put event for %s: %s: %s",
						urls[start+i], aws.ToString(entry.ErrorCode), aws.ToString(entry.ErrorMessage))
				}
			}
			return fmt.Errorf("put events: %d entr(ies) failed", out.FailedEntryCount)
		}
	}
	return nil
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
