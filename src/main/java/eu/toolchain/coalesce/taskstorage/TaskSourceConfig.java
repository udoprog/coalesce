package eu.toolchain.coalesce.taskstorage;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.taskstorage.directory.DirectoryTaskSourceConfig;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(DirectoryTaskSourceConfig.class),
  @JsonSubTypes.Type(NoopTaskSourceConfig.class)
})
public interface TaskSourceConfig {
  TaskSourceComponent setup(EarlyComponent early);
}
