package family.blakey.uptimererer.core.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The EventBridge envelope of a pending check on the {@code uptimererer-bus}: the decidererer
 * publishes these and the checkererer Lambda receives them. Only the fields the checkererer cares
 * about are mapped; the rest of the envelope (id, account, time, ...) is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckRequestedEvent(
    String source, @JsonProperty("detail-type") String detailType, CheckRequest detail) {

  public static final String SOURCE = "uptimererer";
  public static final String DETAIL_TYPE = "check-requested";

  public static CheckRequestedEvent of(CheckRequest detail) {
    return new CheckRequestedEvent(SOURCE, DETAIL_TYPE, detail);
  }
}
