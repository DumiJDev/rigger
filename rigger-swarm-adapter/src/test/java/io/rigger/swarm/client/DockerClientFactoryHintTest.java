package io.rigger.swarm.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed connectivity check is non-fatal, so the log line is the only thing an operator gets.
 * It must name the transport-specific cause — Docker Desktop not running is the most likely
 * first-run experience on Windows.
 */
class DockerClientFactoryHintTest {

    @Test
    void namedPipeHintMentionsDockerDesktop() {
        String hint = DockerClientFactory.remediationHint("npipe:////./pipe/docker_engine");
        assertTrue(hint.contains("Docker Desktop"), hint);
        assertTrue(hint.contains("DOCKER_SOCKET"), hint);
    }

    @Test
    void unixHintMentionsGroupMembership() {
        assertTrue(DockerClientFactory.remediationHint("unix:///var/run/docker.sock").contains("docker' group"));
    }

    @Test
    void tcpHintMentionsTls() {
        assertTrue(DockerClientFactory.remediationHint("tcp://10.0.0.10:2376").contains("tlsCertPath"));
    }
}
