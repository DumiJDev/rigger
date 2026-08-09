package io.rigger.provisioner;

import io.rigger.core.domain.cluster.*;
import io.rigger.core.exception.ManifestValidationException;
import io.rigger.provisioner.cluster.ClusterManifestParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ClusterManifestParserTest {

    private ClusterManifestParser parser;

    @BeforeEach void setUp() { parser = new ClusterManifestParser(); }

    static final String VALID = """
        apiVersion: rigger.io/v1
        kind: Cluster
        metadata:
          name: test-cluster
        spec:
          docker:
            version: "26.1"
            channel: stable
          defaults:
            ssh:
              user: ubuntu
              privateKeyPath: ~/.ssh/id_ed25519
              port: 22
          nodes:
            - name: manager-01
              ip: 10.0.0.10
              role: manager
              primary: true
            - name: worker-01
              ip: 10.0.0.20
              role: worker
        """;

    @Test void parsesValidCluster() throws IOException {
        var spec = parser.parseString(VALID);
        assertEquals("test-cluster", spec.name());
        assertEquals(2, spec.nodes().size());
        assertEquals("manager-01", spec.primaryNode().name());
        assertEquals("ubuntu", spec.defaults().ssh().user());
    }

    @Test void parsesDockerSpec() throws IOException {
        var spec = parser.parseString(VALID);
        assertEquals("26.1", spec.docker().version());
        assertEquals("stable", spec.docker().channel());
    }

    @Test void parsesNodeRoles() throws IOException {
        var spec = parser.parseString(VALID);
        var manager = spec.nodes().get(0);
        var worker = spec.nodes().get(1);
        assertEquals(NodeRole.MANAGER, manager.role());
        assertEquals(NodeRole.WORKER, worker.role());
        assertTrue(manager.primary());
        assertFalse(worker.primary());
    }

    @Test void wrongApiVersion_throws() {
        String yaml = VALID.replace("rigger.io/v1", "k8s.io/v1");
        assertThrows(ManifestValidationException.class, () -> parser.parseString(yaml));
    }

    @Test void wrongKind_throws() {
        String yaml = VALID.replace("kind: Cluster", "kind: Deployment");
        assertThrows(ManifestValidationException.class, () -> parser.parseString(yaml));
    }

    @Test void missingName_throws() {
        String yaml = VALID.replace("name: test-cluster", "name: ");
        assertThrows(Exception.class, () -> parser.parseString(yaml));
    }

    @Test void nodeWithSshOverride_usesNodeCredentials() throws IOException {
        String yaml = VALID + """
            - name: worker-02
              ip: 10.0.0.21
              role: worker
              ssh:
                user: admin
                privateKeyPath: ~/.ssh/admin_key
                port: 22
        """;
        var spec = parser.parseString(yaml);
        var w2 = spec.nodes().stream().filter(n -> n.name().equals("worker-02")).findFirst().orElseThrow();
        assertEquals("admin", spec.resolveCredentials(w2).user());
    }

    @Test void devMode_parsed() throws IOException {
        String yaml = VALID.replace("nodes:", "dev:\n    enabled: true\n  nodes:");
        // dev mode parsing doesn't throw even without primary
        assertDoesNotThrow(() -> parser.parseString(yaml));
    }
}
