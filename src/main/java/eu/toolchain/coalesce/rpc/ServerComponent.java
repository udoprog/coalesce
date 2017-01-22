package eu.toolchain.coalesce.rpc;

import dagger.Component;
import eu.toolchain.coalesce.Server;
import eu.toolchain.coalesce.dagger.EarlyComponent;
import javax.inject.Singleton;

public interface ServerComponent {
  /**
   * Server part.
   */
  Server server();

  @Singleton
  @Component(modules = ServerConfig.class, dependencies = EarlyComponent.class)
  interface Impl extends ServerComponent {
  }
}
