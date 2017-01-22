package eu.toolchain.coalesce.sync.local;

import com.fasterxml.jackson.annotation.JsonTypeName;
import dagger.Module;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.sync.SyncComponent;
import eu.toolchain.coalesce.sync.SyncConfig;
import lombok.Data;

@Data
@Module
@JsonTypeName("local")
public class LocalSyncConfig implements SyncConfig {
  @Override
  public SyncComponent setup(
    final EarlyComponent early
  ) {
    return DaggerLocalSyncComponent.builder().earlyComponent(early).build();
  }

  public static LocalSyncConfig defaults() {
    return new LocalSyncConfig();
  }
}
