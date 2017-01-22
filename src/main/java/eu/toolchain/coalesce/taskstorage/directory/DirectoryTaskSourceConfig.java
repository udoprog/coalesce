package eu.toolchain.coalesce.taskstorage.directory;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import eu.toolchain.coalesce.common.LifeCycle;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.taskstorage.TaskSourceComponent;
import eu.toolchain.coalesce.taskstorage.TaskSourceConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.inject.Singleton;
import lombok.Data;

@JsonTypeName("directory")
@Module
@Data
public class DirectoryTaskSourceConfig implements TaskSourceConfig {
  /**
   * Paths to scan for configuration files.
   */
  private final Optional<List<Path>> paths;

  @Override
  public TaskSourceComponent setup(
    final EarlyComponent early
  ) {
    return DaggerDirectoryTaskSourceComponent
      .builder()
      .directoryTaskSourceConfig(this)
      .earlyComponent(early)
      .build();
  }

  @Singleton
  @Provides
  public List<Path> paths() {
    return paths.orElseGet(ImmutableList::of);
  }

  @Singleton
  @Provides
  public LifeCycle life(final DirectoryTaskSource storage) {
    return registry -> {
      registry.start(storage::start);
      registry.stop(storage::stop);
    };
  }
}
