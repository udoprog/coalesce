package eu.toolchain.coalesce.common;

@FunctionalInterface
public interface LifeCycle {
  void register(LifeCycleRegistry registry);

  /**
   * Create an empty lifecycle that doesn't do anything.
   */
  static LifeCycle empty() {
    return registry -> {
    };
  }
}
