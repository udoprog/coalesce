package eu.toolchain.coalesce.sync;

public interface MemberCallback {
  /**
   * Add a task. The same task ids might be received more than once and should be
   * de-duplicated.
   */
  void addTask(final String id) throws Exception;

  /**
   * Remove a task.
   */
  void removeTask(final String id) throws Exception;
}
