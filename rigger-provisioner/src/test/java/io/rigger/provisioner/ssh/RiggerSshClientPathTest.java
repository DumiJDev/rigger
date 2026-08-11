package io.rigger.provisioner.ssh;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SSH key paths must expand for the forms a Windows user types, not only "~/". */
class RiggerSshClientPathTest {

    private static final Path HOME = Paths.get(System.getProperty("user.home"));

    @Test
    void unixTilde() {
        assertEquals(HOME.resolve(".ssh").resolve("id_ed25519"),
            RiggerSshClient.expandPath("~/.ssh/id_ed25519"));
    }

    @Test
    void windowsTilde() {
        assertEquals(HOME.resolve(".ssh").resolve("id_ed25519"),
            RiggerSshClient.expandPath("~\\.ssh\\id_ed25519"));
    }

    @Test
    void userProfileVariable() {
        assertEquals(HOME.resolve(".ssh").resolve("id_ed25519"),
            RiggerSshClient.expandPath("%USERPROFILE%\\.ssh\\id_ed25519"));
        // Windows env vars are case-insensitive, and forward slashes are legal there too.
        assertEquals(HOME.resolve(".ssh").resolve("id_ed25519"),
            RiggerSshClient.expandPath("%userprofile%/.ssh/id_ed25519"));
    }

    @Test
    void bareTildeIsHome() {
        assertEquals(HOME, RiggerSshClient.expandPath("~"));
    }

    @Test
    void absolutePathsAreUntouched() {
        assertEquals(Paths.get("/home/ci/.ssh/id_rsa"), RiggerSshClient.expandPath("/home/ci/.ssh/id_rsa"));
        // A Windows absolute path must survive verbatim; on Linux Paths.get keeps it as one name.
        assertEquals(Paths.get("C:\\Users\\ci\\.ssh\\id_rsa"),
            RiggerSshClient.expandPath("C:\\Users\\ci\\.ssh\\id_rsa"));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals(HOME.resolve(".ssh").resolve("id_ed25519"),
            RiggerSshClient.expandPath("  ~/.ssh/id_ed25519  "));
    }
}
