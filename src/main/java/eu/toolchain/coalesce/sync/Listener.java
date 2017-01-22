package eu.toolchain.coalesce.sync;

import eu.toolchain.async.AsyncFuture;

@FunctionalInterface
public interface Listener {
  AsyncFuture<Void> cancel();
}
