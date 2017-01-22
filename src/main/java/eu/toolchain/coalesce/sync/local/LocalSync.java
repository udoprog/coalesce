package eu.toolchain.coalesce.sync.local;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.coalesce.sync.LeaderCallback;
import eu.toolchain.coalesce.sync.Listener;
import eu.toolchain.coalesce.sync.MemberCallback;
import eu.toolchain.coalesce.sync.Sync;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A sync implementation that only works inside a single process.
 */
@Slf4j
@Singleton
public class LocalSync implements Sync {
  private final AsyncFramework async;
  private final ExecutorService executor;

  /* a log of operations that this sync implementations have observed, to be replayed by newly
   * elected leaders */
  private final Queue<Consumer<MemberChanges>> memberLog = new LinkedList<>();
  private final Multimap<String, String> assigned = HashMultimap.create();
  private final Map<String, MemberCallback> members = new HashMap<>();

  private LeaderCallback currentLeader = null;

  /* these must always be acquired in the order specified here */
  private final Object memberLock = new Object();
  private final Object leaderLock = new Object();
  private final Object assignedLock = new Object();

  private volatile boolean stopped = false;

  @Inject
  public LocalSync(final AsyncFramework async, final ExecutorService executor) {
    this.async = async;
    this.executor = executor;
  }

  public AsyncFuture<Void> stop() {
    this.stopped = true;
    return async.resolved();
  }

  @Override
  public AsyncFuture<Void> registerLeader(final LeaderCallback callback) {
    return async.call(() -> {
      executor.execute(new LeaderRunner(callback));
      return null;
    });
  }

  @Override
  public AsyncFuture<Listener> registerMember(
    final String memberId, final MemberCallback callback
  ) {
    return async.call(() -> {
      synchronized (memberLock) {
        members.put(memberId, callback);

        synchronized (assignedLock) {
          final Collection<String> taskIds = assigned.get(memberId);

          if (taskIds != null) {
            for (final String taskId : taskIds) {
              callback.addTask(taskId);
            }
          }
        }

        synchronized (leaderLock) {
          memberLog.add(changes -> {
            changes.membersAdded.add(memberId);
            changes.membersRemoved.remove(memberId);
          });

          final LeaderCallback currentLeader = this.currentLeader;

          if (currentLeader != null) {
            currentLeader.memberAdded(memberId);
          }
        }
      }

      return (Listener) () -> {
        synchronized (memberLock) {
          members.remove(memberId);

          synchronized (leaderLock) {
            memberLog.add(changes -> {
              changes.membersAdded.remove(memberId);
              changes.membersRemoved.add(memberId);
            });

            final LeaderCallback currentLeader = this.currentLeader;

            if (currentLeader != null) {
              currentLeader.memberRemoved(memberId);
            }
          }
        }

        return async.resolved();
      };
    });
  }

  @Override
  public AsyncFuture<List<AssignedTask>> getAssignedTasks() {
    return async.call(() -> {
      synchronized (assignedLock) {
        return assigned
          .entries()
          .stream()
          .map(e -> new AssignedTask(e.getKey(), e.getValue()))
          .collect(Collectors.toList());
      }
    });
  }

  @Override
  public AsyncFuture<Void> assign(final String memberId, final String taskId) {
    return async.call(() -> {
      synchronized (memberLock) {
        final MemberCallback member = members.get(memberId);

        synchronized (assignedLock) {
          assigned.put(memberId, taskId);

          // notify member of added task, if they exist
          if (member != null) {
            member.addTask(taskId);
          }
        }
      }

      return null;
    });
  }

  @Override
  public AsyncFuture<Void> unassign(final String memberId, final String taskId) {
    return async.call(() -> {
      synchronized (memberLock) {
        final MemberCallback member = members.get(memberId);

        synchronized (assignedLock) {
          assigned.put(memberId, taskId);

          // notify member of removed task, if they exist
          if (member != null) {
            member.removeTask(taskId);
          }
        }
      }

      return null;
    });
  }

  @RequiredArgsConstructor
  private class LeaderRunner implements Runnable {
    private final LeaderCallback callback;

    @Override
    public void run() {
      try {
        guardedRun();
      } catch (final Exception e) {
        log.error("leader runner failed", e);
      }
    }

    private void guardedRun() throws InterruptedException {
      while (!stopped) {
        final MemberChanges memberChanges;

        synchronized (leaderLock) {
          while (currentLeader != null && !stopped) {
            leaderLock.wait();
          }

          if (stopped) {
            break;
          }

          /* catch up with any unobserved changes as of this election */
          memberChanges = new MemberChanges();

          for (final Consumer<MemberChanges> consumer : memberLog) {
            consumer.accept(memberChanges);
          }

          currentLeader = callback;
        }

        try {
          callback.becameLeader();

          // notify about membership changes
          synchronized (memberLock) {
            memberChanges.membersAdded.forEach(callback::memberAdded);
            memberChanges.membersRemoved.forEach(callback::memberRemoved);
          }

          callback.takeLeadership();
        } catch (final Exception e) {
          log.error("leader threw exception", e);
        }

        /* release leadership */
        synchronized (leaderLock) {
          currentLeader = null;
          leaderLock.notifyAll();
        }
      }

      log.info("stopping leader thread");
    }
  }

  /**
   * A model class indicating members added and changed.
   */
  public static class MemberChanges {
    private final Set<String> membersAdded = new HashSet<>();
    private final Set<String> membersRemoved = new HashSet<>();
  }
}
