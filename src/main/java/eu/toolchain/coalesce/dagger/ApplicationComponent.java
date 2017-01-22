package eu.toolchain.coalesce.dagger;

import dagger.Component;
import eu.toolchain.async.Managed;
import eu.toolchain.coalesce.LeaderProcess;
import eu.toolchain.coalesce.MemberProcess;
import eu.toolchain.coalesce.rpc.RpcComponent;
import eu.toolchain.coalesce.rpc.ServerComponent;
import eu.toolchain.coalesce.sync.SyncComponent;
import eu.toolchain.coalesce.taskstorage.TaskSourceComponent;
import javax.inject.Singleton;

@Singleton
@Component(modules = ApplicationModule.class, dependencies = {
  EarlyComponent.class, ServerComponent.class, RpcComponent.class, SyncComponent.class,
  TaskSourceComponent.class
})
public interface ApplicationComponent
  extends EarlyComponent, ServerComponent, RpcComponent, SyncComponent, TaskSourceComponent {
  Managed<MemberProcess.SyncHandle> memberSync();

  LeaderProcess leader();

  MemberProcess member();
}
