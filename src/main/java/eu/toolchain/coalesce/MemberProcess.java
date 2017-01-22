package eu.toolchain.coalesce;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.ResolvableFuture;
import eu.toolchain.async.StreamCollector;
import eu.toolchain.coalesce.sync.Listener;
import eu.toolchain.coalesce.sync.MemberCallback;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class MemberProcess implements Runnable, MemberCallback {
  private final AsyncFramework async;
  private final int taskParallelism;
  private final ResolvableFuture<Void> join;
  private final GuardedOperation refreshTasks;

  private AsyncFuture<Void> pendingTask = null;

  private final CountDownLatch latch = new CountDownLatch(1);
  private volatile boolean stopped = false;

  private final Object lock = new Object();
  private final Map<String, TaskHandle> tasks = new HashMap<>();
  private final ConcurrentLinkedQueue<Consumer<Changes>> operations = new ConcurrentLinkedQueue<>();

  @Inject
  public MemberProcess(
    final AsyncFramework async, @Named("taskParallelism") final int taskParallelism,
    final ScheduledExecutorService scheduler
  ) {
    this.async = async;
    this.taskParallelism = taskParallelism;
    this.join = async.future();

    this.refreshTasks =
      new GuardedOperation(log, scheduler, async, lock, "refresh-tasks", this::refreshTasks);
  }

  @Override
  public void run() {
    while (!stopped) {
      try {
        latch.await(5, TimeUnit.SECONDS);

        if (stopped) {
          break;
        }

        if (pendingTask == null || pendingTask.isDone()) {
          // log.info("do something...");
        }
      } catch (final Exception e) {
        log.error("Failed to run task", e);
      }
    }

    join.resolve(null);
  }

  public AsyncFuture<Void> stop() {
    stopped = true;
    latch.countDown();
    return join;
  }

  @Override
  public void addTask(final String id) throws Exception {
    operations.add(changes -> {
      changes.tasksAdded.add(id);
      changes.tasksRemoved.remove(id);
    });

    refreshTasks.scheduleExclusive(2, TimeUnit.SECONDS);
  }

  @Override
  public void removeTask(final String id) throws Exception {
    operations.add(changes -> {
      changes.tasksAdded.remove(id);
      changes.tasksRemoved.add(id);
    });

    refreshTasks.scheduleExclusive(2, TimeUnit.SECONDS);
  }

  private AsyncFuture<Void> refreshTasks() {
    final Changes changes = new Changes();

    // consume all pending operations
    while (true) {
      final Consumer<Changes> op = operations.poll();

      if (op == null) {
        break;
      }

      op.accept(changes);
    }

    log.info("tasks: {} added, {} removed", changes.tasksAdded.size(), changes.tasksRemoved.size());

    final List<Callable<AsyncFuture<Void>>> futures = new ArrayList<>();

    for (final String id : changes.tasksRemoved) {
      final TaskHandle handle = tasks.remove(id);

      futures.add(() -> handle.cancel().onFinished(() -> {
        log.trace("-task: {}", id);
      }));
    }

    for (final String id : changes.tasksAdded) {
      final TaskHandle handle = new TaskHandle();
      tasks.put(id, handle);

      futures.add(() -> handle.start().onFinished(() -> {
        log.trace("+task: {}", id);
      }));
    }

    final IgnoreCollector ignore = new IgnoreCollector();
    return async.eventuallyCollect(futures, ignore, taskParallelism);
  }

  private class TaskHandle {
    public AsyncFuture<Void> start() {
      return async.resolved();
    }

    public AsyncFuture<Void> cancel() {
      return async.resolved();
    }
  }

  private static class Changes {
    private final Set<String> tasksAdded = new HashSet<>();
    private final Set<String> tasksRemoved = new HashSet<>();
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
    private final Listener memberListener;
  }
}
