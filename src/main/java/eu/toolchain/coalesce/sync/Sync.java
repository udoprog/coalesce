package eu.toolchain.coalesce.sync;

import eu.toolchain.async.AsyncFuture;
import java.util.List;
import lombok.Data;

/**
 * Global synchronization and task coordination.
 */
public interface Sync {
  /**
   * Register interest in becoming a leader, the given callback will be invoked when leadership is
   * attained.
   */
  AsyncFuture<Void> registerLeader(LeaderCallback callback);

  /**
   * Register a member with the given id.
   */
  AsyncFuture<Listener> registerMember(String memberId, MemberCallback callback);

  /**
   * Get a list of all assigned tasks.
   */
  AsyncFuture<List<AssignedTask>> getAssignedTasks();

  /**
   * Assign the given task from the given member.
   */
  AsyncFuture<Void> assign(String memberId, String taskId);

  /**
   * Unassign the given task from the given member.
   */
  AsyncFuture<Void> unassign(String memberId, String taskId);

  @Data
  class AssignedTask {
    private final String memberId;
    private final String taskId;
  }
}
