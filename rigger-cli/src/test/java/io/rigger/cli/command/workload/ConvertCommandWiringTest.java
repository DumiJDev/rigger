package io.rigger.cli.command.workload;

import io.rigger.cli.command.RiggerCtl;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Picocli subcommand that compiles but was never added to {@code RiggerCtl}'s {@code subcommands}
 * is invisible at runtime — the CLI answers "Unmatched argument" and nothing in the build notices.
 * Same for the option names: {@code -f} is what every other command uses and what the docs promise.
 */
class ConvertCommandWiringTest {

    @Test
    void convertIsRegisteredOnTheRootCommand() {
        var sub = new CommandLine(new RiggerCtl()).getSubcommands().get("convert");
        assertNotNull(sub, "riggerctl convert is not reachable from the root command");
        assertInstanceOf(ConvertCommand.class, sub.getCommandSpec().userObject());
    }

    @Test
    void parsesFileNamespaceAndInsecure() {
        var cmd = new ConvertCommand();
        new CommandLine(cmd).parseArgs("-f", "docker-compose.yml", "-n", "ns-g", "-i", "-q");
        assertEquals(Path.of("docker-compose.yml"), cmd.file);
        assertEquals("ns-g", cmd.namespace);
        assertTrue(cmd.insecure);
        assertTrue(cmd.quiet);
    }

    @Test
    void fileIsMandatory() {
        assertThrows(CommandLine.MissingParameterException.class,
            () -> new CommandLine(new ConvertCommand()).parseArgs());
    }
}
