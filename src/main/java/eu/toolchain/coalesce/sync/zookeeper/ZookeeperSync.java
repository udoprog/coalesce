package eu.toolchain.coalesce.sync.zookeeper;

import com.google.common.collect.ImmutableList;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Borrowed;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ResolvableFuture;
import eu.toolchain.async.Transform;
import eu.toolchain.coalesce.common.LifeCycle;
import eu.toolchain.coalesce.common.LifeCycleRegistry;
import eu.toolchain.coalesce.sync.LeaderCallback;
import eu.toolchain.coalesce.sync.Listener;
import eu.toolchain.coalesce.sync.MemberCallback;
import eu.toolchain.coalesce.sync.Sync;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.BackgroundCallback;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

@Slf4j
@Singleton
public class ZookeeperSync implements LifeCycle, Sync {
  private static final byte[] EMPTY_BYTES = new byte[0];

  public static final String MEMBERS = "/members";
  public static final String ASSIGN = "/assign";

  private final AsyncFramework async;
  private final Managed<CuratorFramework> curator;
  private final ExecutorService executor;
  private final ResolvableFuture<Void> components;

  @Inject
  public ZookeeperSync(
    final AsyncFramework async, final Managed<CuratorFramework> curator,
    final ExecutorService executor
  ) {
    this.async = async;
    this.curator = curator;
    this.executor = executor;
    this.components = async.future();
  }

  @Override
  public AsyncFuture<Void> registerLeader(final LeaderCallback callback) {
    final Borrowed<CuratorFramework> borrow = curator.borrow();
    final CuratorFramework curator = borrow.get();

    final LeaderSelectorListener listener = new LeaderSelectorListener() {
      @Override
      public void takeLeadership(final CuratorFramework curator) throws Exception {
        try {
          guardedTakeLeadership(curator);
        } catch (final Exception e) {
          log.error("Failed to take leadership", e);
          throw e;
        }
      }

      private void guardedTakeLeadership(final CuratorFramework curator) throws Exception {
        // allow for 30 seconds of initial setup

        try {
          callback.becameLeader().get(30, TimeUnit.SECONDS);
        } catch (final Exception e) {
          throw new RuntimeException("Failed to setup new leader", e);
        }

        final PathChildrenCache members =
          new PathChildrenCache(curator, MEMBERS, false, false, executor);

        members.getListenable().addListener(new PathChildrenCacheListener() {
          @Override
          public void childEvent(
            final CuratorFramework client, final PathChildrenCacheEvent event
          ) throws Exception {
            switch (event.getType()) {
              case CHILD_ADDED:
                callback.memberAdded(decodeMemberId(event.getData()));
                break;
              case CHILD_REMOVED:
                callback.memberRemoved(decodeMemberId(event.getData()));
                break;
              default:
                log.info("event: {}", event);
                break;
            }
          }

          private String decodeMemberId(final ChildData data) {
            final String path = data.getPath();

            final int lastSlash = path.lastIndexOf('/');

            if (lastSlash < 0) {
              throw new IllegalArgumentException("illegal path: " + path);
            }

            return path.substring(lastSlash + 1);
          }
        });

        members.start();

        try {
          callback.takeLeadership();
        } catch (final Exception e) {
          log.error("Leadership process threw exception", e);
        }

        members.close();
      }

      @Override
      public void stateChanged(
        final CuratorFramework client, final ConnectionState newState
      ) {
        // TODO: any need to handle this?
      }
    };

    final LeaderSelector leader = new LeaderSelector(curator, "/leader", executor, listener);

    return async.call(() -> {
      leader.autoRequeue();
      leader.start();
      components.onFinished(borrow::release);
      return null;
    });
  }

  @Override
  public AsyncFuture<Listener> registerMember(
    final String memberId, final MemberCallback callback
  ) {
    final Borrowed<CuratorFramework> borrowed = curator.borrow();
    final CuratorFramework curator = borrowed.get();

    final PathChildrenCache assign =
      new PathChildrenCache(curator, ASSIGN + "/" + memberId, false, false, executor);

    assign.getListenable().addListener(new PathChildrenCacheListener() {
      @Override
      public void childEvent(
        final CuratorFramework client, final PathChildrenCacheEvent event
      ) throws Exception {
        switch (event.getType()) {
          case CHILD_ADDED:
            callback.addTask(decodeTaskId(event.getData()));
            break;
          case CHILD_REMOVED:
            callback.removeTask(decodeTaskId(event.getData()));
            break;
          default:
            break;
        }
      }

      private String decodeTaskId(final ChildData data) {
        final String path = data.getPath();

        final int lastSlash = path.lastIndexOf('/');

        if (lastSlash < 0) {
          throw new IllegalArgumentException("illegal path: " + path);
        }

        return path.substring(lastSlash + 1);
      }
    });

    AsyncFuture<Void> startup = async.call(() -> {
      assign.start();
      return null;
    });

    startup = startup.lazyTransform(v -> {
      return async.collectAndDiscard(ImmutableList.of(createMembersNode(memberId)));
    });

    return startup.directTransform(v -> () -> {
      AsyncFuture<Void> shutdown = async.call(() -> {
        assign.close();
        return null;
      });

      shutdown = shutdown.lazyTransform(ignore -> {
        return async.collectAndDiscard(ImmutableList.of(deleteMembersNode(memberId)));
      });

      return shutdown.onFinished(borrowed::release);
    });
  }

  @Override
  public AsyncFuture<List<AssignedTask>> getAssignedTasks() {
    return getChildren(ASSIGN).catchFailed(catchGetChildrenAsEmpty()).lazyTransform(children -> {
      final List<AsyncFuture<List<AssignedTask>>> futures = children
        .stream()
        .map(memberId -> getChildren(ASSIGN + "/" + memberId)
          .catchFailed(catchGetChildrenAsEmpty())
          .directTransform(taskIds -> {
            return taskIds
              .stream()
              .map(taskId -> new AssignedTask(memberId, taskId))
              .collect(Collectors.toList());
          }))
        .collect(Collectors.toList());

      return async.collect(futures).directTransform(lists -> {
        return lists.stream().flatMap(Collection::stream).collect(Collectors.toList());
      });
    });
  }

  private Transform<Throwable, List<String>> catchGetChildrenAsEmpty() {
    return e -> {
      if (e instanceof KeeperException) {
        if (((KeeperException) e).code() == KeeperException.Code.NONODE) {
          return ImmutableList.of();
        }
      }

      throw new RuntimeException(e);
    };
  }

  @Override
  public AsyncFuture<Void> assign(final String memberId, final String taskId) {
    log.info("Assigning {}/{}", memberId, taskId);

    return bind(op -> op
        .create()
        .creatingParentContainersIfNeeded()
        .withMode(CreateMode.PERSISTENT)::inBackground,
      op -> op.forPath(ASSIGN + "/" + memberId + "/" + taskId, EMPTY_BYTES)).directTransform(
      event -> null);
  }

  @Override
  public AsyncFuture<Void> unassign(final String memberId, final String taskId) {
    return bind(op -> op.delete()::inBackground,
      op -> op.forPath(ASSIGN + "/" + memberId + "/" + taskId)).directTransform(event -> null);
  }

  private AsyncFuture<List<String>> getChildren(final String path) {
    return bind(op -> op.getChildren()::inBackground, op -> op.forPath(path)).directTransform(
      CuratorEvent::getChildren);
  }

  /**
   * Create the members node for the given ID.
   *
   * @param memberId id to create for
   * @return a future
   */
  private AsyncFuture<Void> createMembersNode(final String memberId) {
    return bind(op -> op
        .create()
        .creatingParentContainersIfNeeded()
        .withMode(CreateMode.EPHEMERAL)::inBackground,
      op -> op.forPath(MEMBERS + "/" + memberId, EMPTY_BYTES)).directTransform(event -> null);
  }

  /**
   * Delete the members node for the given ID.
   *
   * @param memberId id to create for
   * @return a future
   */
  private AsyncFuture<Void> deleteMembersNode(final String memberId) {
    return bind(op -> op.delete()::inBackground,
      op -> op.forPath(MEMBERS + "/" + memberId)).directTransform(event -> null);
  }

  @Override
  public void register(final LifeCycleRegistry registry) {
    registry.start(this::start);
  }

  private AsyncFuture<Void> start() {
    return curator.start();
  }

  private AsyncFuture<Void> stop() {
    // make sure components release all their resources
    components.resolve(null);
    return curator.stop();
  }

  /**
   * Helper method to bind zookeeper operations to {@link eu.toolchain.async.AsyncFramework}.
   * <p>
   * Note: Curator + Zookeeper actually makes this rather hard through its extensive use of
   * operation-specific typing, so don't be surprised if this method looks strange because of
   * that.
   */
  private <T, U> AsyncFuture<CuratorEvent> bind(
    final Function<CuratorFramework, BiFunction<BackgroundCallback, ExecutorService, T>> first,
    final ThrowingFunction<T, U> second
  ) {
    return curator.doto(curator -> {
      final ResolvableFuture<CuratorEvent> future = async.future();

      final T step1 = first.apply(curator).apply((client, event) -> {
        final KeeperException.Code code = KeeperException.Code.get(event.getResultCode());

        switch (code) {
          case OK:
            future.resolve(event);
            break;
          default:
            future.fail(KeeperException.create(code, event.getPath()));
            break;
        }
      }, executor);

      // ignore return value
      second.apply(step1);
      return future;
    });
  }

  @FunctionalInterface
  interface ThrowingFunction<I, O> {
    O apply(I value) throws Exception;
  }
}
