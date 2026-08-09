package io.rigger.swarm.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwarmAutoConfigurationRegistrationTest {

    @Test
    void shouldRegisterAutoConfigurationInSpringBootImports() throws IOException {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertNotNull(input, "Expected Spring Boot auto-configuration imports file");

            String imports = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(imports.contains("io.rigger.swarm.config.SwarmAdapterAutoConfiguration"),
                    "Expected SwarmAdapterAutoConfiguration to be listed in auto-configuration imports");
        }
    }
}
