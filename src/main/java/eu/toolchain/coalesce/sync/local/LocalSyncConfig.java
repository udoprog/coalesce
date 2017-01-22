package eu.toolchain.coalesce.sync.local;

import com.fasterxml.jackson.annotation.JsonTypeName;
import dagger.Module;
import dagger.Provides;
import eu.toolchain.coalesce.common.LifeCycle;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.sync.SyncComponent;
import eu.toolchain.coalesce.sync.SyncConfig;
import javax.inject.Singleton;
import lombok.Data;

@Data
@Module
@JsonTypeName("local")
public class LocalSyncConfig implements SyncConfig {
  @Override
  public SyncComponent setup(
    final EarlyComponent early
  ) {
    return DaggerLocalSyncComponent.builder().localSyncConfig(this).earlyComponent(early).build();
  }

  @Provides
  @Singleton
  public LifeCycle life(LocalSync sync) {
    return registry -> {
      registry.stop(sync::stop);
    };
  }

  public static LocalSyncConfig defaults() {
    return new LocalSyncConfig();
  }
}
