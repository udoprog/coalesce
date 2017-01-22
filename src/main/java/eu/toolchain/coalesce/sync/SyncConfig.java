package eu.toolchain.coalesce.sync;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.sync.local.LocalSyncConfig;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(LocalSyncConfig.class)
})
public interface SyncConfig {
  SyncComponent setup(EarlyComponent early);
}
