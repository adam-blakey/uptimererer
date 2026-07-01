// Package probe performs a single HTTP(S) uptime check against a site.
package probe

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/adamblakey/uptimererer/internal/events"
)

const maxRedirects = 5

// Result is the outcome of one probe.
type Result struct {
	Up         bool
	HTTPStatus int           // 0 when no response was received
	Latency    time.Duration // time to (failed) response
	Err        string        // empty when Up
}

// Check performs one request against the site using its configured method and
// timeout. The result is Up iff the request completes and the response status
// is one of the site's expected codes. TLS is validated and redirects are
// followed up to a small limit.
func Check(ctx context.Context, site events.SiteConfig) Result {
	client := &http.Client{
		Timeout: time.Duration(site.TimeoutSeconds) * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= maxRedirects {
				return fmt.Errorf("stopped after %d redirects", maxRedirects)
			}
			return nil
		},
	}

	req, err := http.NewRequestWithContext(ctx, site.Method, site.URL, nil)
	if err != nil {
		return Result{Err: fmt.Sprintf("build request: %v", err)}
	}

	start := time.Now()
	resp, err := client.Do(req)
	latency := time.Since(start)
	if err != nil {
		return Result{Latency: latency, Err: err.Error()}
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))

	res := Result{HTTPStatus: resp.StatusCode, Latency: latency}
	if site.StatusExpected(resp.StatusCode) {
		res.Up = true
	} else {
		res.Err = fmt.Sprintf("unexpected status %d", resp.StatusCode)
	}
	return res
}
