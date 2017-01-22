package eu.toolchain.coalesce.storage;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import eu.toolchain.coalesce.dagger.EarlyComponent;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(NoopStorageConfig.class)
})
public interface StorageConfig {
  StorageComponent setup(EarlyComponent early);
}
