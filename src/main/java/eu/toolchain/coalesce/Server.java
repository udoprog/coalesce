package eu.toolchain.coalesce;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ManagedSetup;
import eu.toolchain.coalesce.proto.CoalesceGrpc;
import eu.toolchain.coalesce.proto.GiveTaskReply;
import eu.toolchain.coalesce.proto.Task;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.InetSocketAddress;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

@Singleton
public class Server {
  private final AsyncFramework async;
  private final InetSocketAddress bindAddress;
  private final int maxFrameSize;
  private final NioEventLoopGroup bossGroup;
  private final NioEventLoopGroup workerGroup;
  private final Managed<io.grpc.Server> server;

  @Inject
  public Server(
    final AsyncFramework async, @Named("bindAddress") InetSocketAddress bindAddress,
    @Named("maxFrameSize") int maxFrameSize, @Named("boss") NioEventLoopGroup bossGroup,
    @Named("worker") NioEventLoopGroup workerGroup
  ) {
    this.async = async;
    this.bindAddress = bindAddress;
    this.maxFrameSize = maxFrameSize;
    this.bossGroup = bossGroup;
    this.workerGroup = workerGroup;

    this.server = async.managed(new ManagedSetup<io.grpc.Server>() {
      @Override
      public AsyncFuture<io.grpc.Server> construct() throws Exception {
        return async.call(() -> {
          final io.grpc.Server server = NettyServerBuilder
            .forAddress(bindAddress)
            .addService(new CoalesceImpl())
            .maxMessageSize(maxFrameSize)
            .bossEventLoopGroup(bossGroup)
            .workerEventLoopGroup(workerGroup)
            .build();

          server.start();
          return server;
        });
      }

      @Override
      public AsyncFuture<Void> destruct(final io.grpc.Server value) throws Exception {
        return async.call(() -> {
          value.shutdown();
          return null;
        });
      }
    });
  }

  public AsyncFuture<Void> start() {
    return server.start();
  }

  public class CoalesceImpl extends CoalesceGrpc.CoalesceImplBase {
    @Override
    public void giveTask(
      final Task request, final StreamObserver<GiveTaskReply> responseObserver
    ) {
      responseObserver.onNext(GiveTaskReply.getDefaultInstance());
      responseObserver.onCompleted();
    }
  }
}
