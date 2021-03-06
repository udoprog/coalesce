package eu.toolchain.coalesce.storage;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.collect.ImmutableList;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.model.Task;
import eu.toolchain.coalesce.model.TaskMetadata;
import java.util.List;
import java.util.Optional;
import lombok.Data;

/**
 * NO-OP implementation of storage configuration.
 */
@JsonTypeName("noop")
public class NoopStorageConfig implements StorageConfig {
  @Override
  public StorageComponent setup(final EarlyComponent early) {
    return () -> new NoopStorage(early.async());
  }

  @Data
  public static class NoopStorage implements Storage {
    private final AsyncFramework async;

    @Override
    public AsyncFuture<List<TaskMetadata>> getTaskMetadata() {
      return async.resolved(ImmutableList.of());
    }

    @Override
    public AsyncFuture<Optional<Task>> getTask(
      final String id
    ) {
      return async.resolved(Optional.empty());
    }
  }

  public static NoopStorageConfig defaults() {
    return new NoopStorageConfig();
  }
}
