package eu.toolchain.coalesce.taskstorage.directory;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.coalesce.model.Task;
import eu.toolchain.coalesce.model.TaskMetadata;
import eu.toolchain.coalesce.taskstorage.TaskSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class DirectoryTaskSource implements TaskSource {
  private final AsyncFramework async;
  private final List<Path> paths;
  private final ObjectMapper json;
  private final ObjectMapper yaml;
  private final ScheduledExecutorService scheduler;

  private final AtomicReference<Map<String, TaskPath>> tasks =
    new AtomicReference<>(Collections.emptyMap());
  private final AtomicReference<ScheduledFuture<?>> schedule = new AtomicReference<>();

  @Inject
  public DirectoryTaskSource(
    final AsyncFramework async, final List<Path> paths,
    @Named("application/json") final ObjectMapper json, @Named("text/yaml") final ObjectMapper yaml,
    final ScheduledExecutorService scheduler
  ) {
    this.async = async;
    this.paths = paths;
    this.json = json;
    this.yaml = yaml;
    this.scheduler = scheduler;
  }

  @Override
  public AsyncFuture<List<TaskMetadata>> getTaskMetadata() {
    return async.resolved(
      tasks.get().values().stream().map(TaskPath::metadata).collect(Collectors.toList()));
  }

  private Stream<TaskPath> loadTaskPaths(final Stream<Path> paths) {
    return paths.flatMap(this::loadPath);
  }

  private Stream<TaskPath> loadPath(final Path path) {
    if (Files.isDirectory(path)) {
      try {
        return loadTaskPaths(Files.list(path));
      } catch (final IOException e) {
        throw new RuntimeException(e);
      }
    }

    final String name = path.getFileName().toString();

    final String[] parts = name.split("\\.(?=[^\\.]+$)");

    if (parts.length < 2) {
      log.warn("Could not get extension for file: " + path);
      return Stream.empty();
    }

    final String base = parts[0];
    final String ext = parts[1];

    switch (ext) {
      case "yml":
        return Stream.of(new TaskPath(path, base, yaml));
      case "json":
        return Stream.of(new TaskPath(path, base, json));
      default:
        log.warn("Unrecognized file extension: " + ext);
        return Stream.empty();
    }
  }

  @Override
  public AsyncFuture<Optional<Task>> getTask(final String id) {
    final TaskPath taskPath = tasks.get().get(id);

    if (taskPath == null) {
      return async.resolved(Optional.empty());
    }

    try {
      return async.resolved(Optional.of(taskPath.task()));
    } catch (final Exception e) {
      return async.failed(e);
    }
  }

  AsyncFuture<Void> start() {
    final ScheduledFuture<?> future =
      scheduler.scheduleAtFixedRate(this::sync, 0L, 10L, TimeUnit.SECONDS);

    if (!this.schedule.compareAndSet(null, future)) {
      future.cancel(false);
    }

    return async.resolved();
  }

  AsyncFuture<Void> stop() {
    final ScheduledFuture<?> future = this.schedule.getAndSet(null);

    if (future != null) {
      future.cancel(false);
    }

    return async.resolved();
  }

  private void sync() {
    final Map<String, TaskPath> tasks =
      loadTaskPaths(paths.stream()).collect(Collectors.toMap(TaskPath::getId, t -> t));

    log.trace("Syncing {} task(s) from paths", tasks.size());
    this.tasks.set(tasks);
  }

  @Data
  class TaskPath {
    private final Path path;
    private final String id;
    private final ObjectMapper mapper;

    public TaskMetadata metadata() {
      return new TaskMetadata(id);
    }

    public Task task() {
      try (final BufferedReader reader = Files.newBufferedReader(path)) {
        return mapper.readValue(reader, Task.class);
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
