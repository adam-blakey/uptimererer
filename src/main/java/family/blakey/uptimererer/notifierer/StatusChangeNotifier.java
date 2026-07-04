package family.blakey.uptimererer.notifierer;

import family.blakey.uptimererer.core.db.StateRecord;
import family.blakey.uptimererer.core.db.Status;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Emails (via SNS) when a website goes offline or recovers. The topic ARN comes from {@code
 * uptimererer.notification-topic-arn} — the deploy tool provisions the topic and sets it on the
 * checkererer function; when it's absent (dev, tests) flips are only logged.
 */
@ApplicationScoped
public class StatusChangeNotifier {

  // SNS rejects subjects longer than 100 characters.
  private static final int MAX_SUBJECT_LENGTH = 100;

  private static final Logger LOG = Logger.getLogger(StatusChangeNotifier.class);

  private final SnsClient sns;
  private final Optional<String> topicArn;

  public StatusChangeNotifier(
      SnsClient sns,
      @ConfigProperty(name = "uptimererer.notification-topic-arn") Optional<String> topicArn) {
    this.sns = sns;
    this.topicArn = topicArn;
  }

  public void statusChanged(StateRecord current) {
    String subject = subject(current);
    if (topicArn.isEmpty()) {
      LOG.infof("no notification topic configured; not sending: %s", subject);
      return;
    }

    String message =
        "%s%n%nObserved at %s by uptimererer.".formatted(subject, current.lastChangedAt());
    sns.publish(b -> b.topicArn(topicArn.get()).subject(subject).message(message));
  }

  private static String subject(StateRecord current) {
    String subject =
        "%s is %s"
            .formatted(
                current.websiteId(), current.status() == Status.UP ? "back online" : "offline");
    return subject.length() <= MAX_SUBJECT_LENGTH
        ? subject
        : subject.substring(0, MAX_SUBJECT_LENGTH);
  }
}
