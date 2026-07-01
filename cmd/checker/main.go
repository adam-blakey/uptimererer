// The checker Lambda consumes CheckRequested events from the uptimererer
// EventBridge bus, probes the site over HTTP(S), and persists the resulting
// up/down state to DynamoDB (docs/implementation-plan.md §5.2).
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"time"

	awsevents "github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"

	"github.com/adamblakey/uptimererer/internal/events"
	"github.com/adamblakey/uptimererer/internal/probe"
	"github.com/adamblakey/uptimererer/internal/state"
)

type handler struct {
	repo *state.Repo
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC | log.Lshortfile)

	table := os.Getenv("STATE_TABLE")
	if table == "" {
		log.Fatal("missing required env STATE_TABLE")
	}

	cfg, err := config.LoadDefaultConfig(context.Background())
	if err != nil {
		log.Fatalf("load aws config: %v", err)
	}

	h := handler{repo: state.NewRepo(dynamodb.NewFromConfig(cfg), table)}
	lambda.Start(h.handle)
}

func (h handler) handle(ctx context.Context, evt awsevents.CloudWatchEvent) error {
	var req events.CheckRequested
	if err := json.Unmarshal(evt.Detail, &req); err != nil {
		return fmt.Errorf("parse %s detail: %w", evt.DetailType, err)
	}
	site := req.SiteConfig
	site.ApplyDefaults()
	if err := site.Validate(); err != nil {
		return err
	}

	prev, found, err := h.repo.Get(ctx, site.ID)
	if err != nil {
		return err
	}

	res := probe.Check(ctx, site)
	next := state.Transition(prev, site, res, time.Now())

	var prevLastCheckedAt string
	if found {
		prevLastCheckedAt = prev.LastCheckedAt
	}
	if err := h.repo.PutIfUnchanged(ctx, next, prevLastCheckedAt); err != nil {
		if errors.Is(err, state.ErrStale) {
			log.Printf("site=%s: discarding result, a concurrent check already wrote a newer one", site.ID)
			return nil
		}
		return err
	}

	log.Printf("site=%s url=%s up=%t http=%d latency_ms=%d state=%s failures=%d err=%q",
		site.ID, site.URL, res.Up, res.HTTPStatus, res.Latency.Milliseconds(),
		next.Status, next.ConsecutiveFailures, res.Err)
	return nil
}
