// Package events defines the event contracts shared between the uptimererer
// Lambdas (see docs/implementation-plan.md §4.2).
package events

import (
	"fmt"
	"net/url"
	"slices"
	"time"
)

// EventBridge routing metadata for check requests.
const (
	Source                   = "uptimererer.dispatcher"
	DetailTypeCheckRequested = "CheckRequested"
)

// Defaults applied by SiteConfig.ApplyDefaults.
const (
	DefaultMethod             = "GET"
	DefaultTimeoutSeconds     = 10
	DefaultFailuresBeforeDown = 3
)

// SiteConfig describes one site to monitor. It is stored in SSM under
// /uptimererer/sites and embedded verbatim in every CheckRequested event so
// the checker never has to read SSM itself.
type SiteConfig struct {
	ID                  string `json:"id"`
	URL                 string `json:"url"`
	Method              string `json:"method,omitempty"`
	TimeoutSeconds      int    `json:"timeoutSeconds,omitempty"`
	ExpectedStatusCodes []int  `json:"expectedStatusCodes,omitempty"`
	FailuresBeforeDown  int    `json:"failuresBeforeDown,omitempty"`
}

// ApplyDefaults fills in the optional fields.
func (s *SiteConfig) ApplyDefaults() {
	if s.Method == "" {
		s.Method = DefaultMethod
	}
	if s.TimeoutSeconds <= 0 {
		s.TimeoutSeconds = DefaultTimeoutSeconds
	}
	if len(s.ExpectedStatusCodes) == 0 {
		s.ExpectedStatusCodes = []int{200, 204}
	}
	if s.FailuresBeforeDown <= 0 {
		s.FailuresBeforeDown = DefaultFailuresBeforeDown
	}
}

// Validate reports whether the config is usable for a check.
func (s SiteConfig) Validate() error {
	if s.ID == "" {
		return fmt.Errorf("site config: missing id")
	}
	u, err := url.Parse(s.URL)
	if err != nil {
		return fmt.Errorf("site %q: invalid url: %w", s.ID, err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("site %q: url scheme must be http or https, got %q", s.ID, u.Scheme)
	}
	if u.Host == "" {
		return fmt.Errorf("site %q: url has no host", s.ID)
	}
	return nil
}

// StatusExpected reports whether code counts as a successful response.
func (s SiteConfig) StatusExpected(code int) bool {
	return slices.Contains(s.ExpectedStatusCodes, code)
}

// CheckRequested is the detail payload of a CheckRequested EventBridge event:
// the full site config plus the time the dispatcher requested the check.
type CheckRequested struct {
	SiteConfig
	RequestedAt time.Time `json:"requestedAt"`
}
