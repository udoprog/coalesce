package eu.toolchain.coalesce.sync.local;

import dagger.Component;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.sync.SyncComponent;
import javax.inject.Singleton;

@Singleton
@Component(modules = LocalSyncConfig.class, dependencies = EarlyComponent.class)
public interface LocalSyncComponent extends SyncComponent {
  @Override
  LocalSync sync();
}
