package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"
)

const (
	defaultRegion = "eu-west-2"
	defaultBucket = "test"

	localstackEndpoint = "http://localhost:4566"
	localstackHealth   = "http://localhost:4566/_localstack/health"

	waitTimeout = 60 * time.Second
)

type target string

const (
	targetLocalStack target = "localstack"
	targetAWS        target = "aws"
)

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC | log.Lshortfile)

	var (
		tgt    = flag.String("target", string(targetLocalStack), `Deployment target: "localstack" or "aws"`)
		region = flag.String("region", defaultRegion, "AWS region")
		bucket = flag.String("bucket", defaultBucket, "S3 bucket name")
	)
	flag.Parse()

	ctx := context.Background()

	switch target(*tgt) {
	case targetLocalStack:
		if err := runLocalStack(ctx); err != nil {
			log.Fatalf("localstack: %v", err)
		}
		if err := runApp(ctx, map[string]string{
			"AWS_REGION":   *region,
			"AWS_ENDPOINT": localstackEndpoint,
			"S3_BUCKET":    *bucket,
		}); err != nil {
			log.Fatalf("run app (localstack): %v", err)
		}

	case targetAWS:
		// For AWS, do NOT set AWS_ENDPOINT. The SDK will use real AWS endpoints.
		// Credentials are taken from the normal AWS chain:
		// env vars, shared config, SSO, instance/role credentials, etc.
		if err := runApp(ctx, map[string]string{
			"AWS_REGION": *region,
			"S3_BUCKET":  *bucket,
		}); err != nil {
			log.Fatalf("run app (aws): %v", err)
		}

	default:
		log.Fatalf("unknown -target=%q (expected %q or %q)", *tgt, targetLocalStack, targetAWS)
	}
}

func runLocalStack(ctx context.Context) error {
	composeFile, err := findComposeFile()
	if err != nil {
		return err
	}

	// Start LocalStack via docker compose.
	// We use `docker compose` (newer) rather than `docker-compose` (legacy).
	up := exec.CommandContext(ctx, "docker", "compose", "-f", composeFile, "up", "-d", "localstack")
	up.Stdout = os.Stdout
	up.Stderr = os.Stderr
	if err := up.Run(); err != nil {
		return fmt.Errorf("docker compose up: %w", err)
	}

	// Wait until the health endpoint is ready.
	waitCtx, cancel := context.WithTimeout(ctx, waitTimeout)
	defer cancel()

	if err := waitForHTTP200(waitCtx, localstackHealth); err != nil {
		return fmt.Errorf("waiting for LocalStack health: %w", err)
	}

	return nil
}

func waitForHTTP200(ctx context.Context, url string) error {
	client := &http.Client{
		Timeout: 2 * time.Second,
	}

	ticker := time.NewTicker(750 * time.Millisecond)
	defer ticker.Stop()

	for {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			return fmt.Errorf("build request: %w", err)
		}

		resp, err := client.Do(req)
		if err == nil && resp.Body != nil {
			_ = resp.Body.Close()
		}
		if err == nil && resp.StatusCode == http.StatusOK {
			return nil
		}

		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			// keep trying
		}
	}
}

func runApp(ctx context.Context, env map[string]string) error {
	// This assumes the S3 demo program is the package in ./lambda
	cmd := exec.CommandContext(ctx, "go", "run", "./lambda")

	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Env = mergedEnv(env)

	if err := cmd.Run(); err != nil {
		return fmt.Errorf("go run ./lambda: %w", err)
	}
	return nil
}

func mergedEnv(overrides map[string]string) []string {
	// Start with the current environment, then override/add keys.
	base := os.Environ()

	seen := map[string]bool{}
	out := make([]string, 0, len(base)+len(overrides))

	// Copy existing env, replacing any overridden keys.
	for _, kv := range base {
		k, _, ok := splitEnv(kv)
		if !ok {
			continue
		}
		if v, exists := overrides[k]; exists {
			out = append(out, k+"="+v)
			seen[k] = true
		} else {
			out = append(out, kv)
		}
	}

	// Add any overrides not present.
	for k, v := range overrides {
		if !seen[k] {
			out = append(out, k+"="+v)
		}
	}

	return out
}

func splitEnv(kv string) (key, value string, ok bool) {
	for i := 0; i < len(kv); i++ {
		if kv[i] == '=' {
			return kv[:i], kv[i+1:], true
		}
	}
	return "", "", false
}

func findComposeFile() (string, error) {
	// We look for docker-compose.yml from the current working directory upwards.
	// This makes it resilient to being run from subfolders.
	name := "docker-compose.yml"

	dir, err := os.Getwd()
	if err != nil {
		return "", fmt.Errorf("getwd: %w", err)
	}

	// On Windows, stop at the volume root like C:\.
	for {
		candidate := filepath.Join(dir, name)
		if _, err := os.Stat(candidate); err == nil {
			return candidate, nil
		} else if !errors.Is(err, os.ErrNotExist) {
			return "", fmt.Errorf("stat %s: %w", candidate, err)
		}

		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}

		// Extra guard for Windows drive roots (filepath.Dir("C:\") == "C:\")
		if runtime.GOOS == "windows" && parent == dir {
			break
		}

		dir = parent
	}

	return "", fmt.Errorf("could not find %q in current directory or any parent", name)
}
