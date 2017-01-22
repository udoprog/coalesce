package eu.toolchain.coalesce;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ManagedSetup;
import eu.toolchain.async.ResolvableFuture;
import eu.toolchain.coalesce.proto.CoalesceGrpc;
import eu.toolchain.coalesce.proto.GiveTaskReply;
import eu.toolchain.coalesce.proto.Task;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Data;

@Singleton
public class Rpc {
  private final AsyncFramework async;
  private final NioEventLoopGroup workerGroup;

  @Inject
  public Rpc(
    final AsyncFramework async, @Named("worker") NioEventLoopGroup workerGroup
  ) {
    this.async = async;
    this.workerGroup = workerGroup;
  }

  public AsyncFuture<Client> connect(final InetSocketAddress address) {
    final Managed<CoalesceGrpc.CoalesceStub> channel =
      async.managed(new ManagedSetup<CoalesceGrpc.CoalesceStub>() {
        @Override
        public AsyncFuture<CoalesceGrpc.CoalesceStub> construct() throws Exception {
          return async.call(() -> {
            final ManagedChannel channel = NettyChannelBuilder
              .forAddress(address.getHostName(), address.getPort())
              .usePlaintext(true)
              .executor(workerGroup)
              .eventLoopGroup(workerGroup)
              .build();

            return CoalesceGrpc.newStub(channel);
          });
        }

        @Override
        public AsyncFuture<Void> destruct(final CoalesceGrpc.CoalesceStub value) throws Exception {
          return async.call(() -> {
            final ManagedChannel channel = (ManagedChannel) value.getChannel();
            channel.shutdown();
            channel.awaitTermination(10, TimeUnit.SECONDS);
            return null;
          });
        }
      });

    return channel.start().directTransform(v -> new Client(channel));
  }

  @Data
  public class Client {
    private final Managed<CoalesceGrpc.CoalesceStub> channel;

    public AsyncFuture<Void> giveTask(final String id) {
      return channel.doto(c -> {
        final Task task = Task.newBuilder().setId(id).build();
        final ResolvableFuture<Void> future = async.future();

        c.giveTask(task, new StreamObserver<GiveTaskReply>() {
          @Override
          public void onNext(final GiveTaskReply giveTaskReply) {
            future.resolve(null);
          }

          @Override
          public void onError(final Throwable throwable) {
            future.fail(throwable);
          }

          @Override
          public void onCompleted() {
            if (!future.isDone()) {
              future.fail(new RuntimeException("completed without result"));
            }
          }
        });

        return future;
      });
    }
  }
}
