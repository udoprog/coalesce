package eu.toolchain.coalesce.rpc;

import dagger.Module;
import dagger.Provides;
import io.netty.channel.nio.NioEventLoopGroup;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Data;

@Data
@Module
public class RpcConfig {
  public static RpcConfig defaults() {
    return new RpcConfig();
  }

  @Provides
  @Singleton
  @Named("worker")
  NioEventLoopGroup worker() {
    return new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 4);
  }
}
