package eu.toolchain.coalesce;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;

/**
 * A guarded exclusive async operation that can only have one pending runner at a time.
 */
@RequiredArgsConstructor
class GuardedOperation implements Runnable {
  private final Logger log;
  private final ScheduledExecutorService scheduler;
  private final AsyncFramework async;
  private final Object lock;
  private final String name;
  private final Supplier<AsyncFuture<Void>> supplier;
  private volatile AsyncFuture<Void> pending = null;
  private volatile Optional<ScheduledFuture<?>> scheduled = Optional.empty();

  @Override
  public void run() {
    synchronized (lock) {
      if (pending != null) {
        log.warn("[{}] an operation already in progress");
        return;
      }

      log.info("[{}] starting", name);

      pending = supplier.get();
      pending.onFailed(e -> log.error("[{}] operation failed", name, e));
      pending.onFinished(() -> {
        log.info("[{}] completed", name);

        synchronized (lock) {
          pending = null;
        }
      });
    }
  }

  /**
   * Schedule the current run, but only permit one operation to be pending at a given time.
   */
  public void scheduleExclusive(final long delay, final TimeUnit unit) {
    synchronized (lock) {
      scheduled.ifPresent(f -> f.cancel(false));
      scheduled = Optional.of(scheduler.schedule(this, delay, unit));
    }
  }

  public AsyncFuture<Void> stop() {
    final List<AsyncFuture<Void>> futures = new ArrayList<>();

    futures.add(async.call(() -> {
      synchronized (lock) {
        scheduled.ifPresent(f -> f.cancel(false));
        scheduled = Optional.empty();
      }

      return null;
    }));

    synchronized (lock) {
      if (pending != null) {
        futures.add(pending);
      }
    }

    return async.collectAndDiscard(futures);
  }
}
