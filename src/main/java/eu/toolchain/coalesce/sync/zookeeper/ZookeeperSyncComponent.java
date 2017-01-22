package eu.toolchain.coalesce.sync.zookeeper;

import dagger.Component;
import eu.toolchain.coalesce.common.LifeCycle;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.sync.SyncComponent;
import javax.inject.Singleton;

@Singleton
@Component(modules = ZookeeperSyncConfig.class, dependencies = EarlyComponent.class)
public interface ZookeeperSyncComponent extends SyncComponent {
  @Override
  ZookeeperSync sync();

  @Override
  LifeCycle syncLife();
}
