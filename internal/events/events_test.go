package events

import (
	"encoding/json"
	"testing"
)

func TestApplyDefaults(t *testing.T) {
	s := SiteConfig{ID: "example", URL: "https://example.com"}
	s.ApplyDefaults()

	if s.Method != "GET" || s.TimeoutSeconds != 10 || s.FailuresBeforeDown != 3 {
		t.Errorf("unexpected defaults: %+v", s)
	}
	if !s.StatusExpected(200) || !s.StatusExpected(204) || s.StatusExpected(301) {
		t.Errorf("unexpected default status codes: %v", s.ExpectedStatusCodes)
	}
}

func TestApplyDefaultsKeepsExplicitValues(t *testing.T) {
	s := SiteConfig{
		ID:                  "example",
		URL:                 "https://example.com",
		Method:              "HEAD",
		TimeoutSeconds:      3,
		ExpectedStatusCodes: []int{418},
		FailuresBeforeDown:  1,
	}
	s.ApplyDefaults()

	if s.Method != "HEAD" || s.TimeoutSeconds != 3 || s.FailuresBeforeDown != 1 || !s.StatusExpected(418) {
		t.Errorf("defaults clobbered explicit values: %+v", s)
	}
}

func TestValidate(t *testing.T) {
	tests := []struct {
		name    string
		cfg     SiteConfig
		wantErr bool
	}{
		{"valid https", SiteConfig{ID: "a", URL: "https://example.com/health"}, false},
		{"valid http", SiteConfig{ID: "a", URL: "http://example.com"}, false},
		{"missing id", SiteConfig{URL: "https://example.com"}, true},
		{"missing url", SiteConfig{ID: "a"}, true},
		{"bad scheme", SiteConfig{ID: "a", URL: "ftp://example.com"}, true},
		{"no host", SiteConfig{ID: "a", URL: "https://"}, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if err := tt.cfg.Validate(); (err != nil) != tt.wantErr {
				t.Errorf("Validate() = %v, wantErr %t", err, tt.wantErr)
			}
		})
	}
}

func TestCheckRequestedRoundTrip(t *testing.T) {
	in := `{"id":"example","url":"https://example.com","timeoutSeconds":5,"requestedAt":"2026-07-01T12:00:00Z"}`

	var req CheckRequested
	if err := json.Unmarshal([]byte(in), &req); err != nil {
		t.Fatal(err)
	}
	if req.ID != "example" || req.URL != "https://example.com" || req.TimeoutSeconds != 5 {
		t.Errorf("site config not inlined in detail: %+v", req)
	}
	if req.RequestedAt.IsZero() {
		t.Error("requestedAt not parsed")
	}
}
