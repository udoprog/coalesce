package eu.toolchain.coalesce.rpc;

import dagger.Module;
import dagger.Provides;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.InetSocketAddress;
import java.util.Optional;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Data;

@Data
@Module
public class ServerConfig {
  private static final String DEFAULT_HOST = "0.0.0.0";
  private static final int DEFAULT_PORT = 4192;
  private static final int DEFAULT_MAX_FRAME_SIZE = 10 * 1000000;

  private final Optional<Integer> maxFrameSize;
  private final Optional<String> host;
  private final Optional<Integer> port;

  public static ServerConfig defaults() {
    return new ServerConfig(Optional.empty(), Optional.empty(), Optional.empty());
  }

  @Provides
  @Singleton
  @Named("maxFrameSize")
  public int maxFrameSize() {
    return maxFrameSize.orElse(DEFAULT_MAX_FRAME_SIZE);
  }

  @Provides
  @Singleton
  @Named("bindAddress")
  public InetSocketAddress bindAddress() {
    return new InetSocketAddress(host.orElse(DEFAULT_HOST), port.orElse(DEFAULT_PORT));
  }

  @Provides
  @Singleton
  @Named("boss")
  NioEventLoopGroup boss() {
    return new NioEventLoopGroup(4);
  }

  @Provides
  @Singleton
  @Named("worker")
  NioEventLoopGroup worker() {
    return new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 4);
  }
}
