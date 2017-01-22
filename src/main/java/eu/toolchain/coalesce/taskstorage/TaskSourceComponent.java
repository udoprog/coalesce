package eu.toolchain.coalesce.taskstorage;

import eu.toolchain.coalesce.common.LifeCycle;

public interface TaskSourceComponent {
  TaskSource storage();

  default LifeCycle taskSourceLife() {
    return LifeCycle.empty();
  }
}
