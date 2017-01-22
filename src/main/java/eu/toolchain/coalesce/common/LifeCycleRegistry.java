package eu.toolchain.coalesce.common;

import eu.toolchain.async.AsyncFuture;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class LifeCycleRegistry {
  private final ConcurrentLinkedQueue<Hook> starting = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<Hook> stopping = new ConcurrentLinkedQueue<>();

  public void start(final Hook start) {
    starting.add(start::call);
  }

  public void stop(final Hook stop) {
    stopping.add(stop::call);
  }

  public static Hook namedHook(final String name, final Supplier<AsyncFuture<Void>> supplier) {
    return new Hook() {
      @Override
      public AsyncFuture<Void> call() {
        return supplier.get();
      }

      @Override
      public Optional<String> name() {
        return Optional.of(name);
      }
    };
  }

  @FunctionalInterface
  public interface Hook {
    AsyncFuture<Void> call();

    default Optional<String> name() {
      return Optional.empty();
    }
  }
}
