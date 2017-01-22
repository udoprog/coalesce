package eu.toolchain.coalesce;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.ResolvableFuture;
import eu.toolchain.async.StreamCollector;
import eu.toolchain.coalesce.model.TaskMetadata;
import eu.toolchain.coalesce.sync.LeaderCallback;
import eu.toolchain.coalesce.sync.Listener;
import eu.toolchain.coalesce.sync.Sync;
import eu.toolchain.coalesce.taskstorage.TaskSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class LeaderProcess implements LeaderCallback {
  private final AsyncFramework async;
  private final TaskSource taskSource;
  private final Sync sync;
  private final int syncParallelism;
  private final ResolvableFuture<Void> join;

  /* refresh-members */
  private final GuardedOperation refreshAssignments;

  private AsyncFuture<Void> pendingTask = null;

  private final CountDownLatch latch = new CountDownLatch(1);
  private boolean leader = false;
  private boolean stopped = false;

  private Set<String> taskIds = new HashSet<>();
  private Set<String> memberIds = new HashSet<>();
  private final Object assignedTasksLock = new Object();
  private Multimap<String, String> assignedTasks = ImmutableMultimap.of();
  private final Object lock = new Object();

  @Inject
  public LeaderProcess(
    final AsyncFramework async, final TaskSource taskSource, final Sync sync,
    @Named("syncParallelism") final int syncParallelism, final ScheduledExecutorService scheduler
  ) {
    this.async = async;
    this.taskSource = taskSource;
    this.sync = sync;
    this.syncParallelism = syncParallelism;
    this.join = async.future();

    this.refreshAssignments =
      new GuardedOperation(log, scheduler, async, lock, "refresh-assignments",
        () -> refreshAssignments(new HashSet<>(memberIds), new HashSet<>(taskIds)));
  }

  @Override
  public void takeLeadership() throws Exception {
    while (true) {
      synchronized (lock) {
        if (stopped) {
          join.resolve(null);
          break;
        }

        leader = true;
      }

      if (pendingTask == null || pendingTask.isDone()) {
        final List<AsyncFuture<Void>> tasks = new ArrayList<>();

        tasks.add(synchronizeTaskIds());

        pendingTask = async.collectAndDiscard(tasks);
        pendingTask.onFailed(e -> log.error("Failed to perform all tasks", e));
        pendingTask.onFinished(() -> pendingTask = null);
      } else {
        log.warn("A task is already pending");
      }

      latch.await(5, TimeUnit.SECONDS);
    }
  }

  @Override
  public AsyncFuture<Void> becameLeader() {
    log.info("Elected as Leader");

    if (pendingTask != null) {
      log.warn("Cancelling previous pending task");
      pendingTask.cancel();
      pendingTask = null;
    }

    this.memberIds.clear();

    return sync.getAssignedTasks().directTransform(assigned -> {
      synchronized (assignedTasksLock) {
        this.assignedTasks = assignedToMap(assigned);
      }

      return null;
    });
  }

  AsyncFuture<Void> stop() {
    synchronized (lock) {
      stopped = true;
      latch.countDown();

      if (!leader) {
        return async.resolved();
      }
    }

    final List<AsyncFuture<Void>> futures = new ArrayList<>();
    futures.add(join);
    futures.add(refreshAssignments.stop());
    return async.collectAndDiscard(futures);
  }

  private AsyncFuture<Void> synchronizeTaskIds() throws Exception {
    return taskSource.getTaskMetadata().lazyTransform(tasks -> {
      final Set<String> taskIds =
        tasks.stream().map(TaskMetadata::getId).collect(Collectors.toSet());

      if (!this.taskIds.equals(taskIds)) {
        synchronized (lock) {
          this.taskIds = taskIds;
        }

        refreshAssignments.scheduleExclusive(2, TimeUnit.SECONDS);
      }

      return async.resolved();
    });
  }

  @Override
  public void memberAdded(final String id) {
    synchronized (lock) {
      log.info("Member added: " + id);
      memberIds.add(id);
      scheduleRefresh();
    }
  }

  @Override
  public void memberRemoved(final String id) {
    log.info("Member removed: " + id);

    synchronized (lock) {
      this.memberIds.remove(id);
      scheduleRefresh();
    }
  }

  private AsyncFuture<Void> refreshAssignments(
    final Set<String> memberIds, final Set<String> taskIds
  ) {
    if (memberIds.isEmpty()) {
      log.warn("no members, doing nothing!");
      return async.resolved();
    }

    // the number of tasks to try to allocate to each member
    final int tryAcquire = taskIds.size() / memberIds.size() + 1;

    // keep track of members added
    final Set<String> toAddMembers = new HashSet<>(memberIds);

    // keep track of task ids to add
    final Set<String> toAddTaskIds = new HashSet<>(taskIds);

    // keep track of tasks to remove
    final Set<UnassignTask> toUnassign = new HashSet<>();

    populateEarlyRefreshAssignments(memberIds, taskIds, tryAcquire, toAddMembers, toAddTaskIds,
      toUnassign);

    if (!toAddTaskIds.isEmpty()) {
      if (log.isTraceEnabled()) {
        log.trace("+task-ids: {}", toAddTaskIds);
      } else {
        log.info("+task-ids: {} task(s)", toAddTaskIds.size());
      }
    }

    if (!toUnassign.isEmpty()) {
      if (log.isTraceEnabled()) {
        log.trace("-assign: {}", toUnassign);
      } else {
        log.info("-assign: {} task(s)", toUnassign.size());
      }
    }

    if (!toAddMembers.isEmpty()) {
      if (log.isTraceEnabled()) {
        log.trace("+member: {}", toAddMembers);
      } else {
        log.info("+member: {} member(s)", toAddMembers.size());
      }
    }

    // step 1. remove tasks
    AsyncFuture<Void> future = unassignTasks(toUnassign);

    // step 2. add new tasks
    future = future.lazyTransform(v -> {
      synchronized (lock) {
        // has to be calculated at this point, since #assignedTasks might have changed
        final Set<AssignTask> toAssign = calculateToAssign(toAddMembers, toAddTaskIds, tryAcquire);

        if (!toAssign.isEmpty()) {
          if (log.isTraceEnabled()) {
            log.trace("+assign: {}", toAssign);
          } else {
            log.trace("+assign: {} task(s)", toAssign.size());
          }
        }

        return assignTasks(toAssign);
      }
    });

    return future;
  }

  private void populateEarlyRefreshAssignments(
    final Set<String> memberIds, final Set<String> taskIds, final int tryAcquire,
    final Set<String> toAddMembers, final Set<String> toAddTaskIds,
    final Set<UnassignTask> toUnassign
  ) {
    synchronized (assignedTasksLock) {
      for (final Map.Entry<String, Collection<String>> e : assignedTasks.asMap().entrySet()) {
        toAddMembers.remove(e.getKey());

        final Collection<String> assigned = e.getValue();

        if (!memberIds.contains(e.getKey())) {
          for (final String id : assigned) {
            toUnassign.add(new UnassignTask(e.getKey(), id));
          }

          continue;
        }

        int removeCount = assigned.size() - tryAcquire;

        // remove tasks that no longer exists
        for (final String taskId : e.getValue()) {
          toAddTaskIds.remove(taskId);

          if (!taskIds.contains(taskId)) {
            toUnassign.add(new UnassignTask(e.getKey(), taskId));
            removeCount--;
          }
        }

        final Iterator<String> it = assigned.iterator();

        // remove tasks that are superflous for this node
        while (removeCount-- > 0 && it.hasNext()) {
          final String id = it.next();
          toUnassign.add(new UnassignTask(e.getKey(), id));
          // remove from underlying collection
          it.remove();
          // assign tasks that are removed to someone else
          toAddTaskIds.add(id);
        }
      }
    }
  }

  /**
   * Calculate tasks to assign, and who to assign them to.
   */
  private Set<AssignTask> calculateToAssign(
    final Set<String> toAddMembers, final Set<String> toAddTaskIds, final int tryAcquire
  ) {
    // tasks that should be assigned
    final Set<AssignTask> toAssign = new LinkedHashSet<>();

    final Iterator<String> toAddIt = toAddTaskIds.iterator();

    // prioritize adding tasks to newcomers
    for (final String memberId : toAddMembers) {
      for (int i = 0; i <= tryAcquire && toAddIt.hasNext(); i++) {
        toAssign.add(new AssignTask(memberId, toAddIt.next()));
      }
    }

    synchronized (assignedTasksLock) {
      // go through each already known member and add top up as many tasks as we can
      for (final Map.Entry<String, Collection<String>> e : assignedTasks.asMap().entrySet()) {
        final Collection<String> assigned = e.getValue();

        int addCount = tryAcquire - assigned.size();

        // to many tasks assigned to this
        while (addCount-- > 0 && toAddIt.hasNext()) {
          final String id = toAddIt.next();
          toAssign.add(new AssignTask(e.getKey(), id));
        }
      }
    }

    if (toAddIt.hasNext()) {
      throw new IllegalStateException("not all tasks distributed");
    }

    return toAssign;
  }

  private Multimap<String, String> assignedToMap(final List<Sync.AssignedTask> assigned) {
    final Multimap<String, String> map = HashMultimap.create();

    for (final Sync.AssignedTask assignedTask : assigned) {
      map.put(assignedTask.getMemberId(), assignedTask.getTaskId());
    }

    return map;
  }

  private AsyncFuture<Void> unassignTasks(final Set<UnassignTask> removed) {
    final List<Callable<AsyncFuture<Void>>> removals = new ArrayList<>();

    for (final UnassignTask unassignTask : removed) {
      removals.add(() -> sync
        .unassignTask(unassignTask.getMemberId(), unassignTask.getTaskId())
        .directTransform(ignore -> {
          log.info("-task {} from {}", unassignTask.getTaskId(), unassignTask.getMemberId());

          // lock needed since this happens in parallel
          synchronized (assignedTasksLock) {
            assignedTasks.remove(unassignTask.getMemberId(), unassignTask.getTaskId());
          }

          return null;
        }));
    }

    final IgnoreCollector ignore = new IgnoreCollector();
    return async.eventuallyCollect(removals, ignore, syncParallelism);
  }

  private AsyncFuture<Void> assignTasks(final Set<AssignTask> toAssign) {
    final List<Callable<AsyncFuture<Void>>> additions = new ArrayList<>();

    for (final AssignTask assignTask : toAssign) {
      additions.add(() -> sync
        .assignTask(assignTask.getMemberId(), assignTask.getTaskId())
        .directTransform(ignore -> {
          log.trace("+task {} to {}", assignTask.getTaskId(), assignTask.getMemberId());

          // lock needed since this happens in parallel
          synchronized (assignedTasksLock) {
            assignedTasks.put(assignTask.getMemberId(), assignTask.getTaskId());
          }

          return null;
        }));
    }

    final IgnoreCollector ignore = new IgnoreCollector();
    return async.eventuallyCollect(additions, ignore, syncParallelism);
  }

  private void scheduleRefresh() {
    refreshAssignments.scheduleExclusive(10, TimeUnit.SECONDS);
  }

  /**
   * A task being taken from a member.
   */
  @Data
  private static class AssignTask {
    private final String memberId;
    private final String taskId;
  }

  /**
   * A task being taken from a member.
   */
  @Data
  private static class UnassignTask {
    private final String memberId;
    private final String taskId;
  }

  public static class IgnoreCollector implements StreamCollector<Void, Void> {
    @Override
    public void resolved(final Void result) throws Exception {
    }

    @Override
    public void failed(final Throwable cause) throws Exception {
    }

    @Override
    public void cancelled() throws Exception {
    }

    @Override
    public Void end(final int resolved, final int failed, final int cancelled) throws Exception {
      return null;
    }
  }

  /**
   * Handle for this member running.
   */
  @Data
  public static class SyncHandle {
    private final Listener leaderListener;
  }
}
