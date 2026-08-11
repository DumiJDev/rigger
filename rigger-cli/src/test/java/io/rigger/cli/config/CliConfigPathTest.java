package io.rigger.cli.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The CLI's cert/CA paths come from a hand-edited ~/.rigger/config, so they carry whatever the
 * user's shell would have expanded — including the Windows spellings Java never expands.
 */
class CliConfigPathTest {

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void tildeForms() {
        assertEquals(HOME.resolve(".rigger").resolve("ca.pem"), CliConfig.expandPath("~/.rigger/ca.pem"));
        assertEquals(HOME.resolve(".rigger").resolve("ca.pem"), CliConfig.expandPath("~\\.rigger\\ca.pem"));
        assertEquals(HOME, CliConfig.expandPath("~"));
    }

    @Test
    void userProfileVariable() {
        assertEquals(HOME.resolve(".rigger").resolve("ca.pem"),
            CliConfig.expandPath("%USERPROFILE%\\.rigger\\ca.pem"));
        assertEquals(HOME.resolve(".rigger").resolve("ca.pem"),
            CliConfig.expandPath("%userprofile%/.rigger/ca.pem"));
    }

    @Test
    void absolutePathsAreUntouched() {
        assertEquals(Path.of("/etc/rigger/ca.pem"), CliConfig.expandPath("/etc/rigger/ca.pem"));
        assertEquals(Path.of("C:\\ProgramData\\rigger\\ca.pem"),
            CliConfig.expandPath("C:\\ProgramData\\rigger\\ca.pem"));
    }
}
