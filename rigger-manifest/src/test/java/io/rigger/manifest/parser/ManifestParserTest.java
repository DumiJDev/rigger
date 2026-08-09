package io.rigger.manifest.parser;

import io.rigger.core.domain.resource.*;
import io.rigger.core.exception.ManifestValidationException;
import io.rigger.manifest.validator.ManifestValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManifestParserTest {

    private ManifestParser parser;

    @BeforeEach
    void setUp() {
        parser = new ManifestParser(new ManifestValidator(), new KindRegistry());
    }

    static final String VALID_DEPLOYMENT = """
            apiVersion: rigger.io/v1
            kind: Deployment
            metadata:
              name: payments-api
              namespace: production
              labels:
                app: payments
            spec:
              replicas: 3
              image: myregistry/payments:1.4.2
              hpa:
                minReplicas: 2
                maxReplicas: 10
                targetCPUUtilizationPercentage: 70
                targetMemoryUtilizationPercent: 80
                scaleDownCooldownSeconds: 180
            """;

    static final String VALID_SECRET = """
            apiVersion: rigger.io/v1
            kind: Secret
            metadata:
              name: db-secret
              namespace: production
            spec:
              data:
                db.password: cGFzc3dvcmQxMjM=
            """;

    static final String MULTI_DOC = VALID_DEPLOYMENT + "---\n" + VALID_SECRET;

    @Test
    void parsesDeployment_successfully() throws Exception {
        var results = parser.parseString(VALID_DEPLOYMENT, "test");
        assertEquals(1, results.size());
        var manifest = results.get(0).manifest();
        assertEquals("Deployment", manifest.kind());
        assertEquals("payments-api", manifest.metadata().name());
        assertEquals("production", manifest.metadata().namespace());
        var spec = (DeploymentSpec) manifest.spec();
        assertEquals(3, spec.replicas());
        assertNotNull(spec.hpa());
        assertEquals(2, spec.hpa().minReplicas());
    }

    @Test
    void parsesMultiDocument_bothManifests() throws Exception {
        var results = parser.parseString(MULTI_DOC, "test");
        assertEquals(2, results.size());
        assertEquals("Deployment", results.get(0).manifest().kind());
        assertEquals("Secret", results.get(1).manifest().kind());
    }

    @Test
    void wrongApiVersion_throws() {
        String yaml = """
                apiVersion: k8s.io/v1
                kind: Deployment
                metadata:
                  name: app
                  namespace: prod
                spec:
                  replicas: 1
                  image: app:1.0
                """;
        assertThrows(ManifestValidationException.class, () -> parser.parseString(yaml, "test"));
    }

    @Test
    void unknownKind_throws() {
        String yaml = """
                apiVersion: rigger.io/v1
                kind: StatefulSet
                metadata:
                  name: db
                  namespace: prod
                spec: {}
                """;
        assertThrows(ManifestValidationException.class, () -> parser.parseString(yaml, "test"));
    }

    @Test
    void missingNamespace_throws() {
        String yaml = """
                apiVersion: rigger.io/v1
                kind: Deployment
                metadata:
                  name: app
                spec:
                  replicas: 1
                  image: app:1.0
                """;
        assertThrows(ManifestValidationException.class, () -> parser.parseString(yaml, "test"));
    }

    @Test
    void invalidName_uppercase_throws() {
        String yaml = """
                apiVersion: rigger.io/v1
                kind: Deployment
                metadata:
                  name: PaymentsAPI
                  namespace: production
                spec:
                  replicas: 1
                  image: app:1.0
                """;
        assertThrows(ManifestValidationException.class, () -> parser.parseString(yaml, "test"));
    }

    @Test
    void emptyDocument_isSkipped() throws Exception {
        var results = parser.parseString("\n---\n\n---\n" + VALID_DEPLOYMENT, "test");
        assertEquals(1, results.size());
    }

    @Test
    void secretWithNoData_throws() {
        String yaml = """
                apiVersion: rigger.io/v1
                kind: Secret
                metadata:
                  name: empty-secret
                  namespace: prod
                spec: {}
                """;
        assertThrows(Exception.class, () -> parser.parseString(yaml, "test"));
    }
}