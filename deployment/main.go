package main

import (
	"bytes"
	"context"
	"fmt"
	"log"
	"os"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
)

var (
	awsRegion   string
	awsEndpoint string
	bucketName  string

	s3service *s3.Client
)

func coalesce(value, otherwise string) string {
	if value == "" {
		return otherwise
	}
	return value
}

func init() {
	awsRegion = coalesce(os.Getenv("AWS_REGION"), "eu-west-2")
	awsEndpoint = coalesce(os.Getenv("AWS_ENDPOINT"), "http://localhost:4566")
	bucketName = coalesce(os.Getenv("S3_BUCKET"), "test")

	// Load config.
	awsConfig, err := config.LoadDefaultConfig(context.TODO(),
		config.WithRegion(awsRegion),
	)
	if err != nil {
		log.Fatalf("Cannot load the AWS configs: %s", err)
	}

	// Create S3
	s3service = s3.NewFromConfig(awsConfig, func(o *s3.Options) {
		o.UsePathStyle = true
		o.BaseEndpoint = aws.String(awsEndpoint)
	})
}

func main() {

	// Create Bucket
	_, err := s3service.CreateBucket(context.TODO(), &s3.CreateBucketInput{
		Bucket: aws.String(bucketName),
	})
	if err != nil {
		fmt.Println("Error creating bucket:", err)
	}

	// Put Keys
	s3Key1 := "key1"
	body1 := []byte(fmt.Sprintf("Hello from localstack 1"))
	_, err1 := s3service.PutObject(context.TODO(), &s3.PutObjectInput{
		Bucket:             aws.String(bucketName),
		Key:                aws.String(s3Key1),
		Body:               bytes.NewReader(body1),
		ContentType:        aws.String("text/plain"),
		ContentDisposition: aws.String("attachment"),
	})
	s3Key2 := "key2"
	body2 := []byte(fmt.Sprintf("Hello from localstack 2"))
	_, err2 := s3service.PutObject(context.TODO(), &s3.PutObjectInput{
		Bucket:             aws.String(bucketName),
		Key:                aws.String(s3Key2),
		Body:               bytes.NewReader(body2),
		ContentType:        aws.String("text/plain"),
		ContentDisposition: aws.String("attachment"),
	})
	if err1 != nil || err2 != nil {
		log.Fatal(err1, err2)
	}

	// List Buckets
	result, err := s3service.ListBuckets(context.TODO(), &s3.ListBucketsInput{})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("Buckets:")
	for _, bucket := range result.Buckets {
		fmt.Println(*bucket.Name + ": " + bucket.CreationDate.Format("2006-01-02 15:04:05 Monday"))
	}

	// List Objects
	output, err := s3service.ListObjectsV2(context.TODO(), &s3.ListObjectsV2Input{
		Bucket: aws.String(bucketName),
	})
	if err != nil {
		log.Fatal(err)
	}

	fmt.Printf("List of Objects in %s:\n", bucketName)
	for _, object := range output.Contents {
		fmt.Printf("key=%s size=%d\n", aws.ToString(object.Key), object.Size)
	}

	// Delete Keys
	input := s3.DeleteObjectsInput{
		Bucket: aws.String(bucketName),
		Delete: &types.Delete{
			Objects: []types.ObjectIdentifier{
				{Key: aws.String(s3Key1)},
				{Key: aws.String(s3Key2)},
			},
		},
	}
	_, err = s3service.DeleteObjects(context.TODO(), &input)
	if err != nil {
		log.Fatal(err)
	}
}
