package eu.toolchain.coalesce;

import eu.toolchain.coalesce.rpc.RpcConfig;
import eu.toolchain.coalesce.rpc.ServerConfig;
import eu.toolchain.coalesce.storage.StorageConfig;
import eu.toolchain.coalesce.sync.SyncConfig;
import eu.toolchain.coalesce.taskstorage.TaskSourceConfig;
import java.util.Optional;
import lombok.Data;

@Data
public class CoalesceConfig {
  /**
   * ID of this node. If omitted, will be generated.
   * To allow for stability when nodes come and go a deterministic ID should be used.
   */
  private final Optional<String> id;

  /**
   * Server settings.
   */
  private final Optional<ServerConfig> server;

  /**
   * RPC Client settings.
   */
  private final Optional<RpcConfig> rpc;

  /**
   * Global synchronization.
   */
  private final Optional<SyncConfig> sync;

  /**
   * Low latency source for tasks to-be-scheduled.
   */
  private final Optional<TaskSourceConfig> taskSource;

  /**
   * High latency storage for information on tasks.
   */
  private final Optional<StorageConfig> storage;

  /**
   * How many sync-related tasks a leader may perform in parallel.
   */
  private final Optional<Integer> syncParallelism;

  /**
   * How many task-related tasks a member may perform in parallel.
   */
  private final Optional<Integer> taskParallelism;

  /**
   * Create a default configuration.
   *
   * @return a new default configuration
   */
  public static CoalesceConfig defaults() {
    return new CoalesceConfig(Optional.empty(), Optional.empty(), Optional.empty(),
      Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  /**
   * Merge this configuration with another, adding optional fields as they become available.
   *
   * @param other other config to merge with
   * @return a new merged configuration
   */
  public CoalesceConfig merge(final CoalesceConfig other) {
    return new CoalesceConfig(pickFirst(other.id, id), pickFirst(other.server, server),
      pickFirst(other.rpc, rpc), pickFirst(other.sync, sync),
      pickFirst(other.taskSource, taskSource), pickFirst(other.storage, storage),
      pickFirst(other.syncParallelism, syncParallelism),
      pickFirst(other.taskParallelism, taskParallelism));
  }

  private <T> Optional<T> pickFirst(final Optional<T> first, final Optional<T> second) {
    if (first.isPresent()) {
      return first;
    }

    return second;
  }
}
