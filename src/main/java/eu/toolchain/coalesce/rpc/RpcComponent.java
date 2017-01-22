package eu.toolchain.coalesce.rpc;

import dagger.Component;
import eu.toolchain.coalesce.Rpc;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import javax.inject.Singleton;

public interface RpcComponent {
  /**
   * Client RPC part.
   */
  Rpc rpc();

  @Singleton
  @Component(modules = RpcConfig.class, dependencies = EarlyComponent.class)
  interface Impl extends RpcComponent {
  }
}
