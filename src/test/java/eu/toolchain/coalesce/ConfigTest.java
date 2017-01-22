package eu.toolchain.coalesce;

import com.google.common.io.Resources;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import org.junit.Test;

public class ConfigTest {
    @Test
    public void testConfigs() {
        testConfig("all.yml");

        testConfig("sync-zookeeper.yml");
        testConfig("sync-local.yml");

        testConfig("source-directory.yml");
    }

    /**
     * Test the config with the given name.
     */
    private Coalesce testConfig(final String name) {
        return Coalesce.builder().configReader(() -> {
            try {
                return new BufferedReader(new InputStreamReader(
                    Resources.getResource(ConfigTest.class, name).openStream()));
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }).build();
    }
}
