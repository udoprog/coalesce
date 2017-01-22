package eu.toolchain.coalesce.taskstorage.directory;

import dagger.Component;
import eu.toolchain.coalesce.common.LifeCycle;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.taskstorage.TaskSourceComponent;
import javax.inject.Singleton;

@Singleton
@Component(modules = DirectoryTaskSourceConfig.class, dependencies = EarlyComponent.class)
public interface DirectoryTaskSourceComponent extends TaskSourceComponent {
  DirectoryTaskSource storage();

  @Override
  LifeCycle taskSourceLife();
}
