package state

import (
	"testing"
	"time"

	"github.com/adamblakey/uptimererer/internal/events"
	"github.com/adamblakey/uptimererer/internal/probe"
)

var (
	testSite = events.SiteConfig{ID: "example", URL: "https://example.com", FailuresBeforeDown: 3}
	up       = probe.Result{Up: true, HTTPStatus: 200, Latency: 120 * time.Millisecond}
	down     = probe.Result{Up: false, HTTPStatus: 0, Err: "connection refused"}
	now      = time.Date(2026, 7, 1, 12, 0, 0, 0, time.UTC)
)

func TestTransition(t *testing.T) {
	tests := []struct {
		name         string
		prev         Record
		res          probe.Result
		wantStatus   Status
		wantFailures int
		wantChange   bool // lastStatusChangeAt updated to now
	}{
		{
			name:         "first check success goes up",
			prev:         Record{},
			res:          up,
			wantStatus:   StatusUp,
			wantFailures: 0,
			wantChange:   true,
		},
		{
			name:         "first check failure stays unknown",
			prev:         Record{},
			res:          down,
			wantStatus:   StatusUnknown,
			wantFailures: 1,
			wantChange:   false,
		},
		{
			name:         "up stays up",
			prev:         Record{Status: StatusUp},
			res:          up,
			wantStatus:   StatusUp,
			wantFailures: 0,
			wantChange:   false,
		},
		{
			name:         "single failure does not flip up site",
			prev:         Record{Status: StatusUp},
			res:          down,
			wantStatus:   StatusUp,
			wantFailures: 1,
			wantChange:   false,
		},
		{
			name:         "streak below threshold stays up",
			prev:         Record{Status: StatusUp, ConsecutiveFailures: 1},
			res:          down,
			wantStatus:   StatusUp,
			wantFailures: 2,
			wantChange:   false,
		},
		{
			name:         "streak reaching threshold flips down",
			prev:         Record{Status: StatusUp, ConsecutiveFailures: 2},
			res:          down,
			wantStatus:   StatusDown,
			wantFailures: 3,
			wantChange:   true,
		},
		{
			name:         "unknown site flips down at threshold",
			prev:         Record{Status: StatusUnknown, ConsecutiveFailures: 2},
			res:          down,
			wantStatus:   StatusDown,
			wantFailures: 3,
			wantChange:   true,
		},
		{
			name:         "down stays down without new change timestamp",
			prev:         Record{Status: StatusDown, ConsecutiveFailures: 5},
			res:          down,
			wantStatus:   StatusDown,
			wantFailures: 6,
			wantChange:   false,
		},
		{
			name:         "success resets streak mid-flap",
			prev:         Record{Status: StatusUp, ConsecutiveFailures: 2},
			res:          up,
			wantStatus:   StatusUp,
			wantFailures: 0,
			wantChange:   false,
		},
		{
			name:         "recovery flips down site up",
			prev:         Record{Status: StatusDown, ConsecutiveFailures: 7},
			res:          up,
			wantStatus:   StatusUp,
			wantFailures: 0,
			wantChange:   true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tt.prev.SiteID = testSite.ID
			tt.prev.LastStatusChangeAt = "earlier"

			got := Transition(tt.prev, testSite, tt.res, now)

			if got.Status != tt.wantStatus {
				t.Errorf("Status = %s, want %s", got.Status, tt.wantStatus)
			}
			if got.ConsecutiveFailures != tt.wantFailures {
				t.Errorf("ConsecutiveFailures = %d, want %d", got.ConsecutiveFailures, tt.wantFailures)
			}
			wantChangeAt := "earlier"
			if tt.wantChange {
				wantChangeAt = now.Format(time.RFC3339Nano)
			}
			if got.LastStatusChangeAt != wantChangeAt {
				t.Errorf("LastStatusChangeAt = %q, want %q", got.LastStatusChangeAt, wantChangeAt)
			}
			if got.LastCheckedAt != now.Format(time.RFC3339Nano) {
				t.Errorf("LastCheckedAt = %q, want now", got.LastCheckedAt)
			}
			if got.SiteID != testSite.ID {
				t.Errorf("SiteID = %q, want %q", got.SiteID, testSite.ID)
			}
			if got.LastHTTPStatus != tt.res.HTTPStatus || got.LastError != tt.res.Err {
				t.Errorf("result fields not carried over: %+v", got)
			}
		})
	}
}
