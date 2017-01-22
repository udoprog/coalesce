package eu.toolchain.coalesce.dagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import dagger.Module;
import dagger.Provides;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.TinyAsync;
import eu.toolchain.coalesce.CoalesceConfig;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@RequiredArgsConstructor
@Module
public class EarlyModule {
  private final CoalesceConfig config;

  @Provides
  @Singleton
  AsyncFramework async(final ExecutorService executor) {
    return TinyAsync.builder().executor(executor).recursionSafe(true).build();
  }

  @Provides
  @Singleton
  @Named("application/json")
  ObjectMapper json() {
    final ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new Jdk8Module());
    return mapper;
  }

  @Provides
  @Singleton
  @Named("text/yaml")
  ObjectMapper yaml() {
    final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    mapper.registerModule(new Jdk8Module());
    return mapper;
  }

  @Provides
  @Singleton
  ExecutorService executor() {
    return Executors.newCachedThreadPool(
      new ThreadFactoryBuilder().setNameFormat("coalesce-executor-%d").build());
  }

  @Provides
  @Singleton
  ScheduledExecutorService scheduler() {
    return Executors.newScheduledThreadPool(4,
      new ThreadFactoryBuilder().setNameFormat("coalesce-scheduler-%d").build());
  }

  @Provides
  @Singleton
  @Named("localId")
  String localId() {
    return config.getId().orElseGet(() -> UUID.randomUUID().toString());
  }
}
