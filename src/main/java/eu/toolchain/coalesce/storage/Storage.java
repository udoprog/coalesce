package eu.toolchain.coalesce.storage;

import eu.toolchain.async.AsyncFuture;
import eu.toolchain.coalesce.model.Task;
import eu.toolchain.coalesce.model.TaskMetadata;
import java.util.List;
import java.util.Optional;

public interface Storage {
  /**
   * Get information about all available task.
   *
   * @return a list containing all available tasks
   */
  AsyncFuture<List<TaskMetadata>> getTaskMetadata();

  /**
   * Get a task with the given id, if it exists.
   *
   * @param id id to get
   * @return an optional task
   */
  AsyncFuture<Optional<Task>> getTask(final String id);
}
