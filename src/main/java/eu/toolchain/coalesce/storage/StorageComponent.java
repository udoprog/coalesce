package eu.toolchain.coalesce.storage;

import eu.toolchain.coalesce.common.LifeCycle;

public interface StorageComponent {
  Storage storage();

  default LifeCycle storageLife() {
    return LifeCycle.empty();
  }
}
