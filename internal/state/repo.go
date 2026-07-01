package state

import (
	"context"
	"errors"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// ErrStale is returned by PutIfUnchanged when the stored item changed between
// read and write, i.e. a concurrent invocation already wrote a newer result.
var ErrStale = errors.New("state: item changed since read")

// Repo reads and writes site state records in DynamoDB.
type Repo struct {
	db    *dynamodb.Client
	table string
}

func NewRepo(db *dynamodb.Client, table string) *Repo {
	return &Repo{db: db, table: table}
}

// Get returns the record for siteID; found is false when the site has never
// been checked.
func (r *Repo) Get(ctx context.Context, siteID string) (rec Record, found bool, err error) {
	out, err := r.db.GetItem(ctx, &dynamodb.GetItemInput{
		TableName:      &r.table,
		Key:            map[string]types.AttributeValue{"siteId": &types.AttributeValueMemberS{Value: siteID}},
		ConsistentRead: aws.Bool(true),
	})
	if err != nil {
		return Record{}, false, fmt.Errorf("get %s: %w", siteID, err)
	}
	if len(out.Item) == 0 {
		return Record{}, false, nil
	}
	if err := attributevalue.UnmarshalMap(out.Item, &rec); err != nil {
		return Record{}, false, fmt.Errorf("unmarshal %s: %w", siteID, err)
	}
	return rec, true, nil
}

// PutIfUnchanged writes rec, but only if the stored item's lastCheckedAt still
// equals prevLastCheckedAt (empty means the item must not exist yet), so a
// stale overlapping invocation can't clobber a newer result.
func (r *Repo) PutIfUnchanged(ctx context.Context, rec Record, prevLastCheckedAt string) error {
	item, err := attributevalue.MarshalMap(rec)
	if err != nil {
		return fmt.Errorf("marshal %s: %w", rec.SiteID, err)
	}

	in := &dynamodb.PutItemInput{TableName: &r.table, Item: item}
	if prevLastCheckedAt == "" {
		in.ConditionExpression = aws.String("attribute_not_exists(siteId)")
	} else {
		in.ConditionExpression = aws.String("lastCheckedAt = :prev")
		in.ExpressionAttributeValues = map[string]types.AttributeValue{
			":prev": &types.AttributeValueMemberS{Value: prevLastCheckedAt},
		}
	}

	if _, err := r.db.PutItem(ctx, in); err != nil {
		if _, ok := errors.AsType[*types.ConditionalCheckFailedException](err); ok {
			return ErrStale
		}
		return fmt.Errorf("put %s: %w", rec.SiteID, err)
	}
	return nil
}
