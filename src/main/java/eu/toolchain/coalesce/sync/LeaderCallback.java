package eu.toolchain.coalesce.sync;

import eu.toolchain.async.AsyncFuture;

public interface LeaderCallback {
  /**
   * Called when this node is elected as leaders.
   * Returning from this method will cause the leader to be released.
   */
  void takeLeadership() throws Exception;

  /**
   * Called right before this node becomes the leader.
   * Is always called before other callbacks.
   */
  AsyncFuture<Void> becameLeader();

  /**
   * Called when a member has been added.
   *
   * @param id id of member added.
   */
  void memberAdded(final String id);

  /**
   * Called when a member has been removed.
   *
   * @param id id of member removed
   */
  void memberRemoved(final String id);
}
