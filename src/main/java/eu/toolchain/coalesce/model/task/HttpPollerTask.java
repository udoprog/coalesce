package eu.toolchain.coalesce.model.task;

import com.fasterxml.jackson.annotation.JsonTypeName;
import eu.toolchain.coalesce.model.Task;
import lombok.Data;

@JsonTypeName("http-poller")
@Data
public class HttpPollerTask implements Task {
  private final String target;
}
