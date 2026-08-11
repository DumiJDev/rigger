package io.rigger.manifest.converter;

import io.rigger.core.domain.resource.*;
import io.rigger.manifest.converter.ComposeConverter.Conversion;
import io.rigger.manifest.converter.ComposeConverter.Severity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every case here is a form the converter used to get wrong <em>silently</em> — no exception, no log,
 * just a Deployment that wasn't what the file said. So each test asserts either the value that is now
 * carried across, or the issue that now names the loss.
 */
class ComposeConverterTest {

    private final ComposeConverter converter = new ComposeConverter();

    private Conversion convert(String yaml) throws IOException {
        return converter.convertString(yaml, "ns-test", "test");
    }

    private String messageFor(Conversion c, String path) {
        return c.issues().stream().filter(i -> i.path().equals(path)).findFirst()
            .orElseThrow(() -> new AssertionError("no issue reported for " + path
                + "; got " + c.issues())).message();
    }

    private boolean hasIssue(Conversion c, Severity severity, String path) {
        return c.issues().stream().anyMatch(i -> i.severity() == severity && i.path().equals(path));
    }

    private DeploymentSpec deployment(Conversion c, String name) {
        return (DeploymentSpec) c.manifests().stream()
            .filter(pm -> pm.manifest().kind().equals("Deployment")
                && pm.manifest().metadata().name().equals(name))
            .findFirst().orElseThrow(() -> new AssertionError("no Deployment " + name)).manifest().spec();
    }

    private ServiceSpec service(Conversion c, String name) {
        return (ServiceSpec) c.manifests().stream()
            .filter(pm -> pm.manifest().kind().equals("Service")
                && pm.manifest().metadata().name().equals(name))
            .findFirst().orElseThrow(() -> new AssertionError("no Service " + name)).manifest().spec();
    }

    // ── detection ───────────────────────────────────────────────────────────

    @Test
    void detectsComposeAndRejectsRiggerManifests() {
        assertTrue(converter.isCompose("services:\n  web:\n    image: nginx\n"));
        assertFalse(converter.isCompose("""
            apiVersion: rigger.io/v1
            kind: Deployment
            metadata: {name: web, namespace: ns}
            spec: {image: nginx}
            """));
        assertFalse(converter.isCompose(": not yaml at all ["));
    }

    // ── environment ─────────────────────────────────────────────────────────

    @Test
    void readsEnvironmentInListForm() throws Exception {
        // Used to produce zero variables: properties() on an array node is empty.
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                environment:
                  - LOG_LEVEL=debug
                  - PORT=8080
            """);
        assertEquals(List.of(new EnvVar("LOG_LEVEL", "debug", null), new EnvVar("PORT", "8080", null)),
            deployment(c, "web").env());
    }

    @Test
    void readsEnvironmentInMapForm() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                environment:
                  LOG_LEVEL: debug
            """);
        assertEquals(List.of(new EnvVar("LOG_LEVEL", "debug", null)), deployment(c, "web").env());
    }

    @Test
    void reportsHostPassthroughAndInterpolatedEnvironment() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                environment:
                  - FROM_HOST
                  - TOKEN=${TOKEN}
            """);
        assertTrue(messageFor(c, "services.web.environment.FROM_HOST").contains("host"));
        assertTrue(messageFor(c, "services.web.environment.TOKEN").contains("interpolation"));
        // The interpolated one is still carried over, literally — that is what would run.
        assertEquals(List.of(new EnvVar("TOKEN", "${TOKEN}", null)), deployment(c, "web").env());
    }

    // ── ports ───────────────────────────────────────────────────────────────

    @Test
    void readsLongFormPortsIncludingUdpAndHostPort() throws Exception {
        // Long form used to throw NumberFormatException into an empty catch — the port vanished.
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                ports:
                  - target: 80
                    published: 8080
                  - target: 5353
                    published: 5353
                    protocol: udp
            """);
        var spec = service(c, "web-svc");
        assertEquals(List.of(new ServicePort(8080, 80, "TCP"), new ServicePort(5353, 5353, "UDP")),
            spec.ports());
        assertEquals(ServiceType.LOAD_BALANCER, spec.type());
    }

    @Test
    void keepsHostPortAndProtocolInShortForm() throws Exception {
        // Both used to be discarded: 8080 dropped, /udp coerced to TCP.
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                ports:
                  - "8080:80"
                  - "5353:53/udp"
                  - "127.0.0.1:9000:9000"
            """);
        var spec = service(c, "web-svc");
        assertEquals(List.of(new ServicePort(8080, 80, "TCP"), new ServicePort(5353, 53, "UDP"),
            new ServicePort(9000, 9000, "TCP")), spec.ports());
        assertEquals(ServiceType.LOAD_BALANCER, spec.type());
        assertTrue(messageFor(c, "services.web.ports").contains("host IP"));
    }

    @Test
    void barePortStaysClusterIp() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                ports: ["80"]
            """);
        var spec = service(c, "web-svc");
        assertEquals(List.of(new ServicePort(80, 80, "TCP")), spec.ports());
        assertEquals(ServiceType.CLUSTER_IP, spec.type());
    }

    @Test
    void exposeBecomesClusterIpPort() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                expose: ["9090"]
            """);
        assertEquals(List.of(new ServicePort(9090, 9090, "TCP")), service(c, "web-svc").ports());
        assertTrue(hasIssue(c, Severity.INFO, "services.web.expose"));
    }

    @Test
    void warnsWhenAnExposePortEndsUpPublished() throws Exception {
        // A Rigger Service has one type for every port it carries, so mixing expose: with a
        // published port publishes both — the opposite of what expose: means.
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                ports: ["8080:80"]
                expose: ["9090"]
            """);
        assertEquals(ServiceType.LOAD_BALANCER, service(c, "web-svc").type());
        assertTrue(hasIssue(c, Severity.WARNING, "services.web.expose"));
    }

    @Test
    void reportsPortRangesInsteadOfDroppingThem() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                ports: ["3000-3002:3000-3002"]
            """);
        assertTrue(messageFor(c, "services.web.ports").contains("range"));
        assertEquals(1, c.manifests().size(), "no Service when every port entry was unusable");
    }

    // ── blocking losses ─────────────────────────────────────────────────────

    @Test
    void volumesCommandBuildAndEnvFileAreErrors() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                command: ["nginx", "-g", "daemon off;"]
                entrypoint: /entry.sh
                env_file: .env
                volumes:
                  - ./data:/var/lib/data
              builder:
                build: .
            """);
        for (String path : List.of("services.web.volumes", "services.web.command",
                "services.web.entrypoint", "services.web.env_file", "services.builder.build")) {
            assertTrue(hasIssue(c, Severity.ERROR, path), "expected ERROR for " + path);
        }
        assertTrue(c.hasErrors());
        // …and the ones it can still express are converted, so the caller can see the whole picture.
        assertEquals("nginx:1.27", deployment(c, "web").image());
    }

    @Test
    void healthcheckDependsOnRestartLabelsAndNetworksAreWarnings() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                restart: always
                labels:
                  owner: team-a
                networks: [front]
                depends_on: [db]
                healthcheck:
                  test: ["CMD", "curl", "-f", "http://localhost/"]
              db:
                image: postgres:16
            networks:
              front: {}
            """);
        for (String path : List.of("services.web.healthcheck", "services.web.depends_on",
                "services.web.restart", "services.web.labels", "services.web.networks", "networks")) {
            assertTrue(hasIssue(c, Severity.WARNING, path), "expected WARNING for " + path);
        }
        assertFalse(c.hasErrors(), "none of these change what the workload is");
    }

    @Test
    void unknownKeysAreStillReported() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                some_future_key: 1
            unknown_root: {}
            """);
        assertTrue(hasIssue(c, Severity.WARNING, "services.web.some_future_key"));
        assertTrue(hasIssue(c, Severity.WARNING, "unknown_root"));
    }

    @Test
    void topLevelVolumesIsAnError() throws Exception {
        var c = convert("""
            services:
              db:
                image: postgres:16
            volumes:
              pgdata: {}
            """);
        assertTrue(hasIssue(c, Severity.ERROR, "volumes"));
    }

    // ── deploy ──────────────────────────────────────────────────────────────

    @Test
    void carriesReplicasResourcesAndUpdateConfig() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                deploy:
                  replicas: 3
                  resources:
                    limits: {cpus: "0.5", memory: 512M}
                    reservations: {cpus: "0.25"}
                  update_config:
                    parallelism: 2
                    delay: 1m30s
                    failure_action: rollback
                  placement:
                    constraints: [node.role == worker]
            """);
        var spec = deployment(c, "web");
        assertEquals(3, spec.replicas());
        // 512M is Compose's spelling; MemoryUnit.toBytes only parses Mi/Gi/… and lowercase m/g, so
        // passing it through produced a Deployment that never reconciled (see normalizeMemory).
        assertEquals(new ResourceRequirements("0.5", "512Mi", "0.25", null), spec.resources());
        assertEquals(new RollingUpdateStrategy(2, 90, "ROLLBACK"), spec.strategy());
        assertTrue(hasIssue(c, Severity.WARNING, "services.web.deploy.placement"));
    }

    @Test
    void normalisesComposeMemoryUnitsAndDropsUnparseableOnes() throws Exception {
        var c = convert("""
            services:
              mem-a:
                image: nginx:1.27
                deploy: {resources: {limits: {memory: 1G}}}
              mem-b:
                image: nginx:1.27
                deploy: {resources: {limits: {memory: 1500}}}
              mem-c:
                image: nginx:1.27
                deploy: {resources: {limits: {memory: "lots"}}}
            """);
        assertEquals("1Gi", deployment(c, "mem-a").resources().memoryLimit());
        assertEquals("1500", deployment(c, "mem-b").resources().memoryLimit());
        assertNull(deployment(c, "mem-c").resources(),
            "an unparseable limit is dropped, not passed on to fail every reconcile cycle");
        assertTrue(hasIssue(c, Severity.WARNING, "services.mem-c.deploy.resources.limits.memory"));
    }

    // ── configs / secrets ───────────────────────────────────────────────────

    @Test
    void configFromFileIsAnErrorRatherThanAConfigMapOfFilenames() throws Exception {
        // The old converter produced ConfigMap data {"file": "./nginx.conf"} — the filename as the
        // configuration value — and reported success.
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                configs: [nginx-conf]
            configs:
              nginx-conf:
                file: ./nginx.conf
            """);
        assertTrue(hasIssue(c, Severity.ERROR, "configs.nginx-conf"));
        assertTrue(messageFor(c, "configs.nginx-conf").contains("cannot read"));
        assertTrue(c.manifests().stream().noneMatch(pm -> pm.manifest().kind().equals("ConfigMap")));
        // The reference is still carried, so the fixed-up manifest keeps working.
        assertEquals(List.of("nginx-conf"), deployment(c, "web").configMapRefs());
    }

    @Test
    void inlineConfigContentBecomesAConfigMap() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
            configs:
              app-config:
                content: |
                  key=value
            """);
        var cm = (ConfigMapSpec) c.manifests().stream()
            .filter(pm -> pm.manifest().kind().equals("ConfigMap")).findFirst().orElseThrow()
            .manifest().spec();
        assertEquals("key=value\n", cm.data().get("app-config"));
    }

    @Test
    void secretsAreReportedNeverInvented() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                secrets:
                  - source: db-password
                    target: /run/secrets/db
            secrets:
              db-password:
                file: ./db.txt
              api-key:
                external: true
            """);
        assertTrue(hasIssue(c, Severity.ERROR, "secrets.db-password"));
        assertTrue(hasIssue(c, Severity.WARNING, "secrets.api-key"));
        assertTrue(c.manifests().stream().noneMatch(pm -> pm.manifest().kind().equals("Secret")));
        assertEquals(List.of("db-password"), deployment(c, "web").secretRefs());
        assertTrue(hasIssue(c, Severity.WARNING, "services.web.secrets.target"));
    }

    // ── names ───────────────────────────────────────────────────────────────

    @Test
    void rewritesNamesThatRiggerCannotAccept() throws Exception {
        var c = convert("""
            services:
              Web_App:
                image: nginx:1.27
            """);
        assertEquals("web-app", c.manifests().getFirst().manifest().metadata().name());
        assertTrue(messageFor(c, "services.Web_App").contains("renamed"));
    }

    @Test
    void rejectsANameWithNothingUsableInIt() throws Exception {
        var c = convert("""
            services:
              "_":
                image: nginx:1.27
            """);
        assertTrue(hasIssue(c, Severity.ERROR, "services._"));
        assertTrue(c.manifests().isEmpty());
    }

    @Test
    void missingImageIsAnError() throws Exception {
        // Used to default to "unknown:latest" and get applied.
        var c = convert("""
            services:
              web:
                ports: ["80"]
            """);
        assertTrue(hasIssue(c, Severity.ERROR, "services.web.image"));
        assertTrue(c.manifests().isEmpty());
    }

    // ── YAML round trip ─────────────────────────────────────────────────────

    @Test
    void rendersYamlThatMatchesWhatRiggerAccepts() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                environment:
                  - PORT=8080
                ports:
                  - "8080:80"
            """);
        String yaml = converter.toYaml(c.manifests());

        assertTrue(yaml.contains("apiVersion: \"rigger.io/v1\""), yaml);
        assertEquals(2, yaml.split("(?m)^---\\s*$").length - 1, "one YAML document per manifest");
        // The Kubernetes spelling, not ServiceType's constant name: the JSON Schema enumerates
        // ClusterIP/LoadBalancer, so CLUSTER_IP would parse and then fail validation.
        assertTrue(yaml.contains("type: \"LoadBalancer\""), yaml);
        // Numeric-looking env values stay quoted, or the round trip would change 8080 to an int
        // and "yes" to a boolean.
        assertTrue(yaml.contains("value: \"8080\""), yaml);
        assertFalse(yaml.contains("strategy:"), "the default strategy is noise: " + yaml);
    }

    @Test
    void yamlKeepsAZeroReplicaDeployment() throws Exception {
        var c = convert("""
            services:
              web:
                image: nginx:1.27
                deploy: {replicas: 0}
            """);
        assertTrue(converter.toYaml(c.manifests()).contains("replicas: 0"));
    }
}
