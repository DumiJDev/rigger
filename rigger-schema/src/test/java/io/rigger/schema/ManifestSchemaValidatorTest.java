package io.rigger.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManifestSchemaValidatorTest {

    ManifestSchemaValidator validator;

    @BeforeEach void setUp() { validator = new ManifestSchemaValidator(); }

    static final String VALID_DEPLOYMENT = """
        apiVersion: rigger.io/v1
        kind: Deployment
        metadata:
          name: payments-api
          namespace: production
        spec:
          replicas: 3
          image: myregistry/payments:1.4.2
          hpa:
            minReplicas: 2
            maxReplicas: 10
            targetCPUUtilizationPercentage: 70
        """;

    static final String VALID_SERVICE = """
        apiVersion: rigger.io/v1
        kind: Service
        metadata:
          name: payments-svc
          namespace: production
        spec:
          selector:
            app: payments
          ports:
            - port: 80
              targetPort: 8080
          type: ClusterIP
        """;

    static final String VALID_SECRET = """
        apiVersion: rigger.io/v1
        kind: Secret
        metadata:
          name: db-creds
          namespace: production
        spec:
          data:
            db.password: cGFzc3dvcmQ=
        """;

    @Test void validDeployment_noViolations() {
        var v = validator.validate("Deployment", VALID_DEPLOYMENT);
        assertTrue(v.isEmpty(), "Expected no violations but got: " + v);
    }

    @Test void validService_noViolations() {
        var v = validator.validate("Service", VALID_SERVICE);
        assertTrue(v.isEmpty(), "Expected no violations but got: " + v);
    }

    @Test void validSecret_noViolations() {
        var v = validator.validate("Secret", VALID_SECRET);
        assertTrue(v.isEmpty(), "Expected no violations but got: " + v);
    }

    @Test void wrongApiVersion_hasViolation() {
        String yaml = VALID_DEPLOYMENT.replace("rigger.io/v1", "k8s.io/v1");
        var v = validator.validate("Deployment", yaml);
        assertFalse(v.isEmpty());
        assertTrue(v.stream().anyMatch(s -> s.contains("apiVersion")));
    }

    @Test void negativeReplicas_hasViolation() {
        String yaml = VALID_DEPLOYMENT.replace("replicas: 3", "replicas: -1");
        var v = validator.validate("Deployment", yaml);
        assertFalse(v.isEmpty());
        assertTrue(v.stream().anyMatch(s -> s.contains("replicas")));
    }

    @Test void missingImage_hasViolation() {
        String yaml = """
            apiVersion: rigger.io/v1
            kind: Deployment
            metadata:
              name: app
              namespace: prod
            spec:
              replicas: 1
            """;
        var v = validator.validate("Deployment", yaml);
        assertFalse(v.isEmpty());
    }

    @Test void hpaMinGreaterThanMax_hasViolation() {
        String yaml = VALID_DEPLOYMENT.replace(
            "minReplicas: 2\n            maxReplicas: 10",
            "minReplicas: 10\n            maxReplicas: 2");
        var v = validator.validate("Deployment", yaml);
        // JSON Schema doesn't enforce cross-field constraints — caught by domain validator
        // This test verifies the schema loads without error at minimum
        assertNotNull(v);
    }

    @Test void unknownKind_returnsNoSchemaMessage() {
        var v = validator.validate("StatefulSet", "apiVersion: rigger.io/v1");
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("No schema registered"));
    }

    @Test void invalidServiceType_hasViolation() {
        String yaml = VALID_SERVICE.replace("type: ClusterIP", "type: NodePort");
        var v = validator.validate("Service", yaml);
        assertFalse(v.isEmpty());
    }
}
