package io.rigger.core.domain.cluster;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClusterSpecTest {
    SshCredentials ssh() {
        return SshCredentials.of("ubuntu", "~/.ssh/id");
    }

    ClusterDefaults defaults() {
        return new ClusterDefaults(ssh());
    }

    NodeSpec manager(String name, String ip, boolean primary) {
        return new NodeSpec(name, ip, NodeRole.MANAGER, primary, null, null, false);
    }

    @Test
    void validCluster_builds() {
        var s = new ClusterSpec("c", "ao", DockerSpec.DEFAULT, defaults(),
                List.of(manager("m1", "10.0.0.1", true)), DevMode.DISABLED, null);
        assertEquals("m1", s.primaryNode().name());
    }

    @Test
    void noPrimary_throws() {
        var n = new NodeSpec("m1", "10.0.0.1", NodeRole.MANAGER, false, null, null, false);
        assertThrows(IllegalArgumentException.class,
                () -> new ClusterSpec("c", null, DockerSpec.DEFAULT, defaults(), List.of(n), DevMode.DISABLED, null));
    }

    @Test
    void twoPrimaries_throws() {
        var nodes = List.of(manager("m1", "10.0.0.1", true), manager("m2", "10.0.0.2", true));
        assertThrows(IllegalArgumentException.class,
                () -> new ClusterSpec("c", null, DockerSpec.DEFAULT, defaults(), nodes, DevMode.DISABLED, null));
    }

    @Test
    void resolveCredentials_nodeOverrideWins() {
        var ns = SshCredentials.of("admin", "~/.ssh/admin");
        var w = new NodeSpec("w1", "10.0.0.10", NodeRole.WORKER, false, ns, null, false);
        var m = manager("m1", "10.0.0.1", true);
        var s = new ClusterSpec("c", null, DockerSpec.DEFAULT, defaults(), List.of(m, w), DevMode.DISABLED, null);
        assertEquals("admin", s.resolveCredentials(w).user());
        assertEquals("ubuntu", s.resolveCredentials(m).user());
    }

    @Test
    void devMode_allowsNoPrimary() {
        var n = new NodeSpec("local", "127.0.0.1", NodeRole.MANAGER, false, null, null, false);
        var dev = new DevMode(true, "/var/run/docker.sock");
        assertDoesNotThrow(() -> new ClusterSpec("dev", null, DockerSpec.DEFAULT, defaults(), List.of(n), dev, null));
    }
}