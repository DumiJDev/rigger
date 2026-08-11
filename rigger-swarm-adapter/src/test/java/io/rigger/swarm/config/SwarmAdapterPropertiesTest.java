package io.rigger.swarm.config;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every input form {@code rigger.docker.socket}/{@code host} can take, including the Windows
 * spellings. The result must always be parseable by {@code URI.create}, since
 * {@code DockerClientFactory} hands it straight to the transport builder.
 */
class SwarmAdapterPropertiesTest {

    private static SwarmAdapterProperties props(String socket, String host) {
        var p = new SwarmAdapterProperties();
        p.setSocket(socket);
        p.setHost(host);
        return p;
    }

    private static String resolved(String socket, String host) {
        String uri = props(socket, host).effectiveDockerHost();
        assertDoesNotThrow(() -> URI.create(uri), "not a valid URI: " + uri);
        return uri;
    }

    @Test
    void barePathBecomesUnixUri() {
        assertEquals("unix:///var/run/docker.sock", resolved("/var/run/docker.sock", null));
    }

    @Test
    void explicitSchemesArePreserved() {
        assertEquals("unix:///var/run/docker.sock", resolved("unix:///var/run/docker.sock", null));
        assertEquals("tcp://10.0.0.10:2375", resolved("tcp://10.0.0.10:2375", null));
        assertEquals("npipe:////./pipe/docker_engine", resolved("npipe:////./pipe/docker_engine", null));
    }

    @Test
    void namedPipeShorthandAndBackslashesNormalise() {
        // Every spelling collapses to docker-java's canonical npipe:////./pipe/<name>.
        assertEquals("npipe:////./pipe/docker_engine", resolved("npipe://\\\\.\\pipe\\docker_engine", null));
        assertEquals("npipe:////./pipe/docker_engine", resolved("npipe:\\\\.\\pipe\\docker_engine", null));
        assertEquals("npipe:////./pipe/docker_engine", resolved("NPIPE:////./pipe/docker_engine", null));
        assertEquals("npipe:////./pipe/dockerDesktopLinuxEngine",
            resolved("npipe:////./pipe/dockerDesktopLinuxEngine", null));
        assertEquals("npipe:////./pipe/docker_engine", resolved("\\\\.\\pipe\\docker_engine", null));
        assertEquals("npipe:////./pipe/docker_engine", resolved("//./pipe/docker_engine", null));
    }

    @Test
    void hostWinsOverSocketAndIsTrimmed() {
        assertEquals("tcp://10.0.0.10:2376", resolved("/var/run/docker.sock", " tcp://10.0.0.10:2376 "));
        // socket may be absent entirely when a host is configured
        assertEquals("tcp://10.0.0.10:2376", resolved(null, "tcp://10.0.0.10:2376"));
    }

    @Test
    void blankHostMeansLocalSocket() {
        // application.yaml's ${DOCKER_HOST:} resolves to "" when the env var is unset.
        assertTrue(props("/var/run/docker.sock", "").isLocalSocket());
        assertEquals("unix:///var/run/docker.sock", resolved("/var/run/docker.sock", ""));
        assertFalse(props("/var/run/docker.sock", "tcp://x:2375").isLocalSocket());
    }
}
