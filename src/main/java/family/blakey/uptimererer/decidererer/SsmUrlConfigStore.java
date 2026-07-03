package family.blakey.uptimererer.decidererer;

import family.blakey.uptimererer.core.events.CheckRequest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Parameter;

/**
 * URL configs live in Systems Manager Parameter Store, one parameter per website under {@code
 * uptimererer.url-parameter-prefix} whose value is the URL to check (see {@code make add-url}). A
 * parameter holding an invalid URL is skipped with a warning rather than blocking the tick — one
 * bad config mustn't stop every other website's checks.
 */
@ApplicationScoped
public class SsmUrlConfigStore implements UrlConfigStore {

  private static final Logger LOG = Logger.getLogger(SsmUrlConfigStore.class);

  private final SsmClient ssm;
  private final String prefix;

  public SsmUrlConfigStore(
      SsmClient ssm, @ConfigProperty(name = "uptimererer.url-parameter-prefix") String prefix) {
    this.ssm = ssm;
    this.prefix = prefix;
  }

  @Override
  public List<CheckRequest> urls() {
    List<CheckRequest> urls = new ArrayList<>();
    ssm.getParametersByPathPaginator(b -> b.path(prefix).recursive(true)).stream()
        .flatMap(page -> page.parameters().stream())
        .forEach(parameter -> parse(parameter).ifPresent(urls::add));
    return List.copyOf(urls);
  }

  private Optional<CheckRequest> parse(Parameter parameter) {
    try {
      return Optional.of(CheckRequest.Validator.validate(new CheckRequest(parameter.value())));
    } catch (IllegalArgumentException invalid) {
      LOG.warnf("skipping url config %s: %s", parameter.name(), invalid.getMessage());
      return Optional.empty();
    }
  }
}
