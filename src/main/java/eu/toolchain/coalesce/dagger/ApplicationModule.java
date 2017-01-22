package eu.toolchain.coalesce.dagger;

import dagger.Module;
import dagger.Provides;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ManagedSetup;
import eu.toolchain.coalesce.CoalesceConfig;
import eu.toolchain.coalesce.LeaderProcess;
import eu.toolchain.coalesce.MemberProcess;
import eu.toolchain.coalesce.sync.Sync;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Module
public class ApplicationModule {
  public static final int DEFAULT_SYNC_PARALLELISM = 20;
  public static final int DEFAULT_TASK_PARALLELISM = 5;

  private final CoalesceConfig config;

  @Provides
  @Singleton
  @Named("syncParallelism")
  public int syncParallelism() {
    return config.getSyncParallelism().orElse(DEFAULT_SYNC_PARALLELISM);
  }

  @Provides
  @Singleton
  @Named("taskParallelism")
  public int taskParallelism() {
    return config.getTaskParallelism().orElse(DEFAULT_TASK_PARALLELISM);
  }

  @Provides
  @Singleton
  public Managed<MemberProcess.SyncHandle> memberSync(
    final AsyncFramework async, @Named("localId") final String localId, final Sync sync,
    final MemberProcess member
  ) {
    return async.managed(new ManagedSetup<MemberProcess.SyncHandle>() {
      @Override
      public AsyncFuture<MemberProcess.SyncHandle> construct() throws Exception {
        return sync.registerMember(localId, member).directTransform(MemberProcess.SyncHandle::new);
      }

      @Override
      public AsyncFuture<Void> destruct(final MemberProcess.SyncHandle value) throws Exception {
        return value.getMemberListener().cancel();
      }
    });
  }

  @Provides
  @Singleton
  public Managed<LeaderProcess.SyncHandle> leaderSync(
    final AsyncFramework async, @Named("localId") final String localId, final Sync sync,
    final LeaderProcess leader
  ) {
    return async.managed(new ManagedSetup<LeaderProcess.SyncHandle>() {
      @Override
      public AsyncFuture<LeaderProcess.SyncHandle> construct() throws Exception {
        return sync.registerLeader(localId, leader).directTransform(LeaderProcess.SyncHandle::new);
      }

      @Override
      public AsyncFuture<Void> destruct(final LeaderProcess.SyncHandle value) throws Exception {
        return value.getLeaderListener().cancel();
      }
    });
  }
}
