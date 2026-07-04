package family.blakey.uptimererer.core.db;

import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Whether a website answered its last check. */
public enum Status {
  UP,
  DOWN;

  /** Stores the status by name; the enhanced client has no built-in enum support. */
  public static final class Converter implements AttributeConverter<Status> {
    @Override
    public AttributeValue transformFrom(Status status) {
      return AttributeValue.fromS(status.name());
    }

    @Override
    public Status transformTo(AttributeValue value) {
      return Status.valueOf(value.s());
    }

    @Override
    public EnhancedType<Status> type() {
      return EnhancedType.of(Status.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
      return AttributeValueType.S;
    }
  }
}
