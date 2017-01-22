package eu.toolchain.coalesce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.ResolvableFuture;
import eu.toolchain.coalesce.common.LifeCycle;
import eu.toolchain.coalesce.common.LifeCycleRegistry;
import eu.toolchain.coalesce.dagger.ApplicationComponent;
import eu.toolchain.coalesce.dagger.ApplicationModule;
import eu.toolchain.coalesce.dagger.DaggerApplicationComponent;
import eu.toolchain.coalesce.dagger.DaggerEarlyComponent_Impl;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import eu.toolchain.coalesce.dagger.EarlyModule;
import eu.toolchain.coalesce.rpc.DaggerRpcComponent_Impl;
import eu.toolchain.coalesce.rpc.DaggerServerComponent_Impl;
import eu.toolchain.coalesce.rpc.RpcComponent;
import eu.toolchain.coalesce.rpc.RpcConfig;
import eu.toolchain.coalesce.rpc.ServerComponent;
import eu.toolchain.coalesce.rpc.ServerConfig;
import eu.toolchain.coalesce.sync.SyncComponent;
import eu.toolchain.coalesce.sync.SyncConfig;
import eu.toolchain.coalesce.sync.local.LocalSyncConfig;
import eu.toolchain.coalesce.sync.zookeeper.ZookeeperSyncConfig;
import eu.toolchain.coalesce.taskstorage.NoopTaskSourceConfig;
import eu.toolchain.coalesce.taskstorage.TaskSourceComponent;
import eu.toolchain.coalesce.taskstorage.TaskSourceConfig;
import eu.toolchain.coalesce.taskstorage.directory.DirectoryTaskSourceConfig;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

@Slf4j
@Data
public class Coalesce {
  private final ApplicationComponent component;
  private final ResolvableFuture<Void> join;

  private final List<LifeCycleRegistry.Hook> starting;
  private final List<LifeCycleRegistry.Hook> postStart;
  private final List<LifeCycleRegistry.Hook> stopping;
  private final List<LifeCycleRegistry.Hook> postStop;

  /**
   * Special executor service used to run late processes during shutdown.
   */
  private final ExecutorService lateExecutor;

  /**
   * Start coalesce and all subsystems it depends on.
   * <p>
   * This method is not synchronized, make sure to only call it once!
   *
   * @return a future associated with the starting of coalesce
   */
  public AsyncFuture<Void> start() {
    return callHooks("start", starting.stream()).lazyTransform(v -> {
      return callHooks("post-start", postStart.stream());
    });
  }

  /**
   * Start coalesce and all subsystems it depends on.
   * <p>
   * This method is not synchronized, make sure to only call it once!
   *
   * @return a future associated with the stopping of coalesce
   */
  public AsyncFuture<Void> stop() {
    final AsyncFuture<Void> future = callHooks("stop", stopping.stream()).lazyTransform(v -> {
      return callHooks("post-stop", postStop.stream());
    });

    future.onResolved(join::resolve);
    future.onFailed(join::fail);
    future.onCancelled(join::cancel);

    return future;
  }

  private AsyncFuture<Void> callHooks(
    final String stage, final Stream<LifeCycleRegistry.Hook> hooks
  ) {
    final List<CalledHook> calledHooks =
      hooks.map(h -> new CalledHook(h, h.call())).collect(Collectors.toList());

    final List<AsyncFuture<Void>> futures =
      calledHooks.stream().map(CalledHook::getFuture).collect(Collectors.toList());

    final AsyncFramework async = component.async();

    return async.call(() -> {
      try {
        async.collectAndDiscard(futures).get(5, TimeUnit.SECONDS);
      } catch (final TimeoutException e) {
        log.error("[{}] Some operations timed out:", stage);

        int i = 0;

        for (final CalledHook called : calledHooks) {
          if (!called.getFuture().isDone()) {
            final LifeCycleRegistry.Hook hook = called.getHook();
            log.info("[{}]  #{}: {}", stage, i++, hook.name().orElseGet(hook::toString));
          }
        }

        throw e;
      } catch (final Exception e) {
        throw e;
      }

      return null;
    }, lateExecutor);
  }

  /**
   * Wait until coalesce shuts down.
   *
   * @throws InterruptedException if interrupted
   * @throws ExecutionException if the stopping caused an exception
   */
  public void join() throws InterruptedException, ExecutionException {
    join.get();
  }

  public static void main(String[] argv) throws Exception {
    final Builder builder = Coalesce.builder();

    final Parameters parameters;

    try {
      parameters = parseArguments(argv);
    } catch (final Exception e) {
      log.error("Failed to parse argument", e);
      System.exit(1);
      return;
    }

    if (parameters.isHelp()) {
      System.exit(0);
      return;
    }

    parameters.getConfigPaths().forEach(path -> {
      final Path p = Paths.get(path).toAbsolutePath();

      if (!Files.isReadable(p)) {
        throw new RuntimeException("Config not readable: " + p);
      }

      builder.configPath(p);
    });

    final List<Path> paths = new ArrayList<>();
    paths.add(Paths.get("coalesce.yml"));

    for (final Path p : paths) {
      log.info("Checking: " + p.toAbsolutePath());

      if (Files.isReadable(p)) {
        builder.configPath(p);
      }
    }

    final Coalesce coalesce = builder.build();

    try {
      coalesce.start().get();
    } catch (final Exception e) {
      log.error("Startup failed", e);
      System.exit(1);
    }

    final Thread shutdown = new Thread(() -> {
      try {
        coalesce.stop().get(20, TimeUnit.SECONDS);
      } catch (final Exception e) {
        log.error("Failed to run shutdown hook", e);
      }
    });

    shutdown.setName("coalesce-shutdown-hook");
    Runtime.getRuntime().addShutdownHook(shutdown);

    log.info("Coalesce started!");

    try {
      coalesce.join();
    } catch (final Exception e) {
      log.error("Joining failed", e);
    }

    log.info("Coalesce exiting, bye bye!");
    System.exit(0);
  }

  /**
   * Read a list of supplied configuration files.
   * <p>
   * The resulting configuration will be the combination of all supplied configuration files.
   *
   * @param configReaders readers to configuration files to load
   * @return a configuration file
   * @see CoalesceConfig#merge(CoalesceConfig)
   */
  static CoalesceConfig readConfigs(final List<Supplier<BufferedReader>> configReaders) {
    // use a default config as basis
    CoalesceConfig config = CoalesceConfig.defaults();

    final ObjectMapper parser = config();

    for (final Supplier<BufferedReader> reader : configReaders) {
      try (final BufferedReader file = reader.get()) {
        config = config.merge(parser.readValue(file, CoalesceConfig.class));
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    }

    return config;
  }

  static Parameters parseArguments(final String[] args) throws Exception {
    final Parameters params = new Parameters();

    final CmdLineParser parser = new CmdLineParser(params);

    parser.parseArgument(args);

    if (params.isHelp()) {
      parser.printUsage(System.out);
      return params;
    }

    return params;
  }

  /**
   * Create an ObjectMapper capable of reading coalesce configuration files.
   *
   * @return a new ObjectMapper
   */
  static ObjectMapper config() {
    final ObjectMapper m = new ObjectMapper(new YAMLFactory());

        /* storage implementations */
    m.registerSubtypes(TaskSourceConfig.class, DirectoryTaskSourceConfig.class);

        /* sync implementations */
    m.registerSubtypes(SyncConfig.class, LocalSyncConfig.class);
    m.registerSubtypes(SyncConfig.class, ZookeeperSyncConfig.class);

        /* support java.util.Optional */
    m.registerModule(new Jdk8Module());
    return m;
  }

  /**
   * Create a new builder for the coalesce service.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  @RequiredArgsConstructor
  public static class Builder {
    private List<Supplier<BufferedReader>> configReaders = new ArrayList<>();

    /**
     * Configure loading configuration file from the given path.
     */
    public Builder configPath(final Path path) {
      this.configReaders.add(() -> {
        try {
          return Files.newBufferedReader(path);
        } catch (final Exception e) {
          throw new RuntimeException(e);
        }
      });

      return this;
    }

    /**
     * Configure loading configuration file from the given reader.
     * <p>
     * The reader supplied must be newly opened, and must not have been closed.
     */
    public Builder configReader(final Supplier<BufferedReader> configReader) {
      this.configReaders.add(configReader);
      return this;
    }

    public Coalesce build() {
      final CoalesceConfig config = readConfigs(configReaders);

      final List<LifeCycle> lifeCycles = new ArrayList<>();

      final EarlyComponent early =
        DaggerEarlyComponent_Impl.builder().earlyModule(new EarlyModule(config)).build();

      final ServerComponent server = DaggerServerComponent_Impl
        .builder()
        .earlyComponent(early)
        .serverConfig(config.getServer().orElseGet(ServerConfig::defaults))
        .build();

      final RpcComponent rpc = DaggerRpcComponent_Impl
        .builder()
        .earlyComponent(early)
        .rpcConfig(config.getRpc().orElseGet(RpcConfig::defaults))
        .build();

      final SyncComponent sync = config.getSync().orElseGet(LocalSyncConfig::defaults).setup(early);
      lifeCycles.add(sync.syncLife());

      final TaskSourceComponent taskStorage =
        config.getTaskSource().orElseGet(NoopTaskSourceConfig::defaults).setup(early);
      lifeCycles.add(taskStorage.taskSourceLife());

      final ApplicationComponent component = DaggerApplicationComponent
        .builder()
        .syncComponent(sync)
        .taskSourceComponent(taskStorage)
        .earlyComponent(early)
        .serverComponent(server)
        .rpcComponent(rpc)
        .applicationModule(new ApplicationModule(config))
        .build();

      final ResolvableFuture<Void> join = component.async().future();

      final LifeCycleRegistry registry = new LifeCycleRegistry();

      for (final LifeCycle life : lifeCycles) {
        life.register(registry);
      }

      final List<LifeCycleRegistry.Hook> starting = new ArrayList<>(registry.getStarting());
      final List<LifeCycleRegistry.Hook> postStart = new ArrayList<>();
      final List<LifeCycleRegistry.Hook> stopping = new ArrayList<>(registry.getStopping());
      final List<LifeCycleRegistry.Hook> postStop = new ArrayList<>();

            /* run leader thread */
      postStart.add(LifeCycleRegistry.namedHook("leader thread", () -> {
        return component.leaderSync().start();
      }));

      stopping.add(LifeCycleRegistry.namedHook("leader thread", () -> {
        return component.leaderSync().stop().lazyTransform(ignore -> {
          return component.leader().stop();
        });
      }));

      /* run member thread */
      final MemberProcess member = component.member();

      postStart.add(LifeCycleRegistry.namedHook("member thread", () -> {
        return component.memberSync().start().directTransform(ignore -> {
          component.executor().execute(member);
          return null;
        });
      }));

      stopping.add(LifeCycleRegistry.namedHook("member thread", () -> {
        return component.memberSync().stop().lazyTransform(ignore -> {
          return member.stop();
        });
      }));

      final ExecutorService lateExecutor = Executors.newCachedThreadPool(
        new ThreadFactoryBuilder().setNameFormat("coalesce-post-hook-%d").build());

      postStop.add(LifeCycleRegistry.namedHook("thread pool", () -> {
        return component.async().call(() -> {
          final ExecutorService shutdown = component.executor();
          shutdown.shutdown();

          if (!shutdown.awaitTermination(5, TimeUnit.SECONDS)) {
            log.error("Failed to shut down thread pool:");

            int i = 0;

            for (final Runnable runnable : shutdown.shutdownNow()) {
              log.error("  runnable#{}: {}", i++, runnable);
            }
          }

          return null;
        }, lateExecutor);
      }));

      postStop.add(LifeCycleRegistry.namedHook("scheduler", () -> {
        return component.async().call(() -> {
          final ScheduledExecutorService shutdown = component.scheduler();
          shutdown.shutdown();

          if (!shutdown.awaitTermination(5, TimeUnit.SECONDS)) {
            log.error("Failed to shut down thread pool:");

            int i = 0;

            for (final Runnable runnable : shutdown.shutdownNow()) {
              log.error("  runnable#{}: {}", i++, runnable);
            }
          }

          return null;
        }, lateExecutor);
      }));

      return new Coalesce(component, join, starting, postStart, stopping, postStop, lateExecutor);
    }
  }

  @Data
  public static class CalledHook {
    private final LifeCycleRegistry.Hook hook;
    private final AsyncFuture<Void> future;
  }

  @ToString
  @Data
  public static class Parameters {
    @Option(name = "-C", aliases = {"--config"}, metaVar = "<path>",
      usage = "Use the given configuration file, can be provided multiple times.")
    private List<String> configPaths = new ArrayList<>();

    @Option(name = "-h", aliases = {"--help"}, help = true, usage = "Display help.")
    private boolean help;

    @Argument
    private List<String> extra = new ArrayList<>();
  }
}
