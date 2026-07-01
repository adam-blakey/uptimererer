// Package state models per-site up/down state and persists it to DynamoDB
// (table contract in docs/implementation-plan.md §4.3).
package state

import (
	"time"

	"github.com/adamblakey/uptimererer/internal/events"
	"github.com/adamblakey/uptimererer/internal/probe"
)

// Status is a site's current state.
type Status string

const (
	StatusUp      Status = "UP"
	StatusDown    Status = "DOWN"
	StatusUnknown Status = "UNKNOWN"
)

// Record is the single DynamoDB item kept per site (partition key: siteId).
type Record struct {
	SiteID              string `dynamodbav:"siteId" json:"siteId"`
	Status              Status `dynamodbav:"status" json:"status"`
	ConsecutiveFailures int    `dynamodbav:"consecutiveFailures" json:"consecutiveFailures"`
	LastCheckedAt       string `dynamodbav:"lastCheckedAt" json:"lastCheckedAt"`
	LastStatusChangeAt  string `dynamodbav:"lastStatusChangeAt,omitempty" json:"lastStatusChangeAt,omitempty"`
	LastHTTPStatus      int    `dynamodbav:"lastHttpStatus" json:"lastHttpStatus"`
	LatencyMs           int64  `dynamodbav:"latencyMs" json:"latencyMs"`
	LastError           string `dynamodbav:"lastError,omitempty" json:"lastError,omitempty"`
}

// Transition applies one probe result to the previous record (the zero Record
// for a never-checked site) and returns the next record. A success resets the
// failure streak and flips the site UP; failures only flip it DOWN once the
// streak reaches the site's failuresBeforeDown threshold (anti-flapping).
func Transition(prev Record, site events.SiteConfig, res probe.Result, now time.Time) Record {
	next := prev
	next.SiteID = site.ID
	if next.Status == "" {
		next.Status = StatusUnknown
	}
	next.LastCheckedAt = now.UTC().Format(time.RFC3339Nano)
	next.LastHTTPStatus = res.HTTPStatus
	next.LatencyMs = res.Latency.Milliseconds()
	next.LastError = res.Err

	if res.Up {
		next.ConsecutiveFailures = 0
		if next.Status != StatusUp {
			next.Status = StatusUp
			next.LastStatusChangeAt = next.LastCheckedAt
		}
		return next
	}

	next.ConsecutiveFailures = prev.ConsecutiveFailures + 1
	if next.Status != StatusDown && next.ConsecutiveFailures >= site.FailuresBeforeDown {
		next.Status = StatusDown
		next.LastStatusChangeAt = next.LastCheckedAt
	}
	return next
}
