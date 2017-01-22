package eu.toolchain.coalesce.sync.zookeeper;

import com.fasterxml.jackson.annotation.JsonTypeName;
import dagger.Module;
import dagger.Provides;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ManagedSetup;
import eu.toolchain.coalesce.common.LifeCycle;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.sync.SyncComponent;
import eu.toolchain.coalesce.sync.SyncConfig;
import java.util.Optional;
import javax.inject.Singleton;
import lombok.Data;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

@Data
@Module
@JsonTypeName("zookeeper")
public class ZookeeperSyncConfig implements SyncConfig {
  private static final String DEFUALT_ZOOKEEPER_URL = "127.0.0.1:2181/coalesce";

  private final Optional<String> url;

  @Override
  public SyncComponent setup(
    final EarlyComponent early
  ) {
    return DaggerZookeeperSyncComponent
      .builder()
      .earlyComponent(early)
      .zookeeperSyncConfig(this)
      .build();
  }

  @Provides
  @Singleton
  public LifeCycle life(final ZookeeperSync sync) {
    return sync;
  }

  @Provides
  @Singleton
  public Managed<CuratorFramework> curator(final AsyncFramework async) {
    final String zookeeperUrl = this.url.orElse(DEFUALT_ZOOKEEPER_URL);

    return async.managed(new ManagedSetup<CuratorFramework>() {
      @Override
      public AsyncFuture<CuratorFramework> construct() throws Exception {
        final ExponentialBackoffRetry retryPolicy =
          new ExponentialBackoffRetry(1000, Integer.MAX_VALUE);

        final CuratorFramework curator = CuratorFrameworkFactory
          .builder()
          .connectString(zookeeperUrl)
          .retryPolicy(retryPolicy)
          .build();

        return async.call(() -> {
          curator.start();
          return curator;
        });
      }

      @Override
      public AsyncFuture<Void> destruct(
        final CuratorFramework value
      ) throws Exception {
        return async.call(() -> {
          value.close();
          return null;
        });
      }
    });
  }
}
