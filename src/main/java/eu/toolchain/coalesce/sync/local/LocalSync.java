package eu.toolchain.coalesce.sync.local;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.coalesce.sync.LeaderCallback;
import eu.toolchain.coalesce.sync.Listener;
import eu.toolchain.coalesce.sync.MemberCallback;
import eu.toolchain.coalesce.sync.Sync;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class LocalSync implements Sync {
  private final Set<String> scheduledTaskIds =
    Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

  private volatile Optional<String> version = Optional.empty();

  private final AsyncFramework async;

  @Inject
  public LocalSync(final AsyncFramework async) {
    this.async = async;
  }

  @Override
  public AsyncFuture<Void> registerLeader(final LeaderCallback callback) {
    throw new RuntimeException("not implemented");
  }

  @Override
  public AsyncFuture<Listener> registerMember(
    final String memberId, final MemberCallback callback
  ) {
    throw new RuntimeException("not implemented");
  }

  @Override
  public AsyncFuture<List<AssignedTask>> getAssignedTasks() {
    throw new RuntimeException("not implemented");
  }

  @Override
  public AsyncFuture<Void> assign(final String memberId, final String taskId) {
    throw new RuntimeException("not implemented");
  }

  @Override
  public AsyncFuture<Void> unassign(final String memberId, final String taskId) {
    throw new RuntimeException("not implemented");
  }
}
