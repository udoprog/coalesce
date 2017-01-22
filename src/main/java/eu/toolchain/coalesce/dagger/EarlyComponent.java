package eu.toolchain.coalesce.dagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Component;
import eu.toolchain.async.AsyncFramework;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Named;
import javax.inject.Singleton;

public interface EarlyComponent {
  AsyncFramework async();

  @Named("application/json")
  ObjectMapper json();

  @Named("text/yaml")
  ObjectMapper yaml();

  ExecutorService executor();

  ScheduledExecutorService scheduler();

  @Named("localId")
  String localId();

  @Singleton
  @Component(modules = EarlyModule.class)
  interface Impl extends EarlyComponent {
  }
}
