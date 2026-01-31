package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
)

const (
	defaultAWSRegion   = "us-east-1"
	defaultAWSEndpoint = "http://localhost:4566"
	defaultBucketName  = "test"

	requestTimeout = 10 * time.Second
)

type settings struct {
	region   string
	endpoint string
	bucket   string
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC | log.Lshortfile)

	// One context for the whole run; every AWS call uses a per-call timeout derived from it.
	rootCtx := context.Background()

	cfg := loadSettingsFromEnv()
	s3Client, err := newS3Client(rootCtx, cfg)
	if err != nil {
		log.Fatalf("create s3 client: %v", err)
	}

	// 1) Ensure the bucket exists (idempotent-ish).
	if err := ensureBucket(rootCtx, s3Client, cfg.bucket); err != nil {
		log.Fatalf("ensure bucket %q: %v", cfg.bucket, err)
	}

	// 2) Upload two objects.
	objects := map[string][]byte{
		"key1": []byte("Hello from localstack 1"),
		"key2": []byte("Hello from localstack 2"),
	}
	for key, body := range objects {
		if err := putTextObject(rootCtx, s3Client, cfg.bucket, key, body); err != nil {
			log.Fatalf("put object %q: %v", key, err)
		}
	}

	// 3) List buckets.
	if err := printBuckets(rootCtx, s3Client); err != nil {
		log.Fatalf("list buckets: %v", err)
	}

	// 4) List objects in our bucket.
	if err := printObjects(rootCtx, s3Client, cfg.bucket); err != nil {
		log.Fatalf("list objects in %q: %v", cfg.bucket, err)
	}

	// 5) Delete objects we created.
	if err := deleteObjects(rootCtx, s3Client, cfg.bucket, "key1", "key2"); err != nil {
		log.Fatalf("delete objects: %v", err)
	}
}

func loadSettingsFromEnv() settings {
	return settings{
		region:   envOrDefault("AWS_REGION", defaultAWSRegion),
		endpoint: envOrDefault("AWS_ENDPOINT", defaultAWSEndpoint),
		bucket:   envOrDefault("S3_BUCKET", defaultBucketName),
	}
}

func envOrDefault(name, def string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return def
}

func newS3Client(ctx context.Context, cfg settings) (*s3.Client, error) {
	// Small timeout for config to load (it can read shared config/credentials files).
	ctx, cancel := context.WithTimeout(ctx, requestTimeout)
	defer cancel()

	awsCfg, err := config.LoadDefaultConfig(ctx, config.WithRegion(cfg.region))
	if err != nil {
		return nil, fmt.Errorf("load aws config: %w", err)
	}

	// For LocalStack / custom S3 endpoints:
	// - UsePathStyle: bucket in the path (more compatible for localhost endpoints).
	// - BaseEndpoint: override endpoint without a custom resolver.
	client := s3.NewFromConfig(awsCfg, func(o *s3.Options) {
		o.UsePathStyle = true
		o.BaseEndpoint = aws.String(cfg.endpoint)
	})

	return client, nil
}

func ensureBucket(ctx context.Context, s3Client *s3.Client, bucket string) error {
	ctx, cancel := context.WithTimeout(ctx, requestTimeout)
	defer cancel()

	_, err := s3Client.CreateBucket(ctx, &s3.CreateBucketInput{
		Bucket: aws.String(bucket),
	})
	if err == nil {
		return nil
	}

	// Treat "already exists / already owned" as a success.
	var alreadyOwned *types.BucketAlreadyOwnedByYou
	if errors.As(err, &alreadyOwned) {
		return nil
	}

	// Some S3-compatible services return generic "BucketAlreadyExists" even when it is effectively OK.
	var alreadyExists *types.BucketAlreadyExists
	if errors.As(err, &alreadyExists) {
		return nil
	}

	return fmt.Errorf("create bucket: %w", err)
}

func putTextObject(ctx context.Context, s3Client *s3.Client, bucket, key string, body []byte) error {
	ctx, cancel := context.WithTimeout(ctx, requestTimeout)
	defer cancel()

	_, err := s3Client.PutObject(ctx, &s3.PutObjectInput{
		Bucket:             aws.String(bucket),
		Key:                aws.String(key),
		Body:               bytes.NewReader(body),
		ContentType:        aws.String("text/plain; charset=utf-8"),
		ContentDisposition: aws.String("attachment"),
	})
	if err != nil {
		return fmt.Errorf("put object to bucket=%q key=%q: %w", bucket, key, err)
	}
	return nil
}

func printBuckets(ctx context.Context, s3Client *s3.Client) error {
	ctx, cancel := context.WithTimeout(ctx, requestTimeout)
	defer cancel()

	out, err := s3Client.ListBuckets(ctx, &s3.ListBucketsInput{})
	if err != nil {
		return fmt.Errorf("list buckets: %w", err)
	}

	fmt.Println("Buckets:")
	for _, b := range out.Buckets {
		name := aws.ToString(b.Name)
		created := "<unknown>"
		if b.CreationDate != nil {
			created = b.CreationDate.UTC().Format("2006-01-02 15:04:05 Monday")
		}
		fmt.Printf("- %s: %s\n", name, created)
	}

	return nil
}

func printObjects(ctx context.Context, s3Client *s3.Client, bucket string) error {
	ctx, cancel := context.WithTimeout(ctx, requestTimeout)
	defer cancel()

	out, err := s3Client.ListObjectsV2(ctx, &s3.ListObjectsV2Input{
		Bucket: aws.String(bucket),
	})
	if err != nil {
		return fmt.Errorf("list objects v2: %w", err)
	}

	fmt.Printf("List of Objects in %s:\n", bucket)
	for _, obj := range out.Contents {
		fmt.Printf("key=%s size=%d\n", aws.ToString(obj.Key), obj.Size)
	}
	return nil
}

func deleteObjects(ctx context.Context, s3Client *s3.Client, bucket string, keys ...string) error {
	if len(keys) == 0 {
		return nil
	}

	ctx, cancel := context.WithTimeout(ctx, requestTimeout)
	defer cancel()

	ids := make([]types.ObjectIdentifier, 0, len(keys))
	for _, k := range keys {
		ids = append(ids, types.ObjectIdentifier{Key: aws.String(k)})
	}

	out, err := s3Client.DeleteObjects(ctx, &s3.DeleteObjectsInput{
		Bucket: aws.String(bucket),
		Delete: &types.Delete{
			Objects: ids,
			Quiet:   aws.Bool(true),
		},
	})
	if err != nil {
		return fmt.Errorf("delete objects request failed: %w", err)
	}

	// DeleteObjects can succeed overall but still report per-key failures.
	if len(out.Errors) > 0 {
		// Report the first error to keep output concise; these can be aggregated if wanted.
		e := out.Errors[0]
		return fmt.Errorf("delete objects partially failed (example): key=%s code=%s message=%s",
			aws.ToString(e.Key), aws.ToString(e.Code), aws.ToString(e.Message))
	}

	return nil
}
