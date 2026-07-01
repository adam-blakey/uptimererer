package probe

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/adamblakey/uptimererer/internal/events"
)

func site(url string) events.SiteConfig {
	s := events.SiteConfig{ID: "test", URL: url}
	s.ApplyDefaults()
	return s
}

func TestCheckUp(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	res := Check(context.Background(), site(srv.URL))
	if !res.Up {
		t.Fatalf("want up, got %+v", res)
	}
	if res.HTTPStatus != http.StatusOK {
		t.Errorf("HTTPStatus = %d, want 200", res.HTTPStatus)
	}
	if res.Err != "" {
		t.Errorf("Err = %q, want empty", res.Err)
	}
}

func TestCheckUnexpectedStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	res := Check(context.Background(), site(srv.URL))
	if res.Up {
		t.Fatalf("want down, got %+v", res)
	}
	if res.HTTPStatus != http.StatusServiceUnavailable {
		t.Errorf("HTTPStatus = %d, want 503", res.HTTPStatus)
	}
	if res.Err == "" {
		t.Error("want error message for unexpected status")
	}
}

func TestCheckCustomExpectedStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTeapot)
	}))
	defer srv.Close()

	s := site(srv.URL)
	s.ExpectedStatusCodes = []int{http.StatusTeapot}
	if res := Check(context.Background(), s); !res.Up {
		t.Fatalf("want up for expected 418, got %+v", res)
	}
}

func TestCheckConnectionRefused(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	srv.Close() // free the port so the check gets connection refused

	res := Check(context.Background(), site(srv.URL))
	if res.Up {
		t.Fatalf("want down, got %+v", res)
	}
	if res.HTTPStatus != 0 {
		t.Errorf("HTTPStatus = %d, want 0 (no response)", res.HTTPStatus)
	}
	if res.Err == "" {
		t.Error("want error message")
	}
}

func TestCheckTimeout(t *testing.T) {
	block := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-block
	}))
	defer func() { close(block); srv.Close() }()

	s := site(srv.URL)
	s.TimeoutSeconds = 1
	res := Check(context.Background(), s)
	if res.Up {
		t.Fatalf("want down on timeout, got %+v", res)
	}
	if res.Err == "" {
		t.Error("want timeout error message")
	}
}

func TestCheckFollowsRedirects(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/final", http.StatusFound)
	})
	mux.HandleFunc("/final", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	if res := Check(context.Background(), site(srv.URL)); !res.Up {
		t.Fatalf("want up after redirect, got %+v", res)
	}
}

func TestCheckRedirectLoop(t *testing.T) {
	n := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n++
		http.Redirect(w, r, fmt.Sprintf("/loop%d", n), http.StatusFound)
	}))
	defer srv.Close()

	res := Check(context.Background(), site(srv.URL))
	if res.Up {
		t.Fatalf("want down for redirect loop, got %+v", res)
	}
	if !strings.Contains(res.Err, "redirects") {
		t.Errorf("Err = %q, want redirect limit error", res.Err)
	}
}

func TestCheckTLSValidation(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	// The server's cert is self-signed, so a validating client must fail.
	res := Check(context.Background(), site(srv.URL))
	if res.Up {
		t.Fatalf("want down for untrusted certificate, got %+v", res)
	}
	if res.HTTPStatus != 0 {
		t.Errorf("HTTPStatus = %d, want 0", res.HTTPStatus)
	}
}
