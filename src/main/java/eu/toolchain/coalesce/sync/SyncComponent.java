package eu.toolchain.coalesce.sync;

import eu.toolchain.coalesce.common.LifeCycle;

public interface SyncComponent {
  /**
   * Access synchronization implementation.
   */
  Sync sync();

  default LifeCycle syncLife() {
    return LifeCycle.empty();
  }
}
