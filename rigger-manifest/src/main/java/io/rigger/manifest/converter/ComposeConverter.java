package io.rigger.manifest.converter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.rigger.core.domain.resource.*;
import io.rigger.manifest.parser.ParsedManifest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Converts a Docker Compose file into Rigger manifests <em>and a report of everything it could not
 * carry across</em>.
 *
 * <h2>Why the report is the important part</h2>
 * This converter used to read only {@code image}, {@code deploy.replicas}, the map form of
 * {@code environment} and the short form of {@code ports}, and drop every other Compose key without
 * a word — the class javadoc claimed each ignored key was logged; nothing was. A file declaring
 * {@code volumes:} and a {@code command:} produced a Deployment that ran a different process with no
 * storage, reported as "created". Losing information is sometimes unavoidable (Rigger has no volume
 * primitive); losing it silently is the bug.
 *
 * <p>So conversion now always yields a {@link Conversion}: the manifests plus an ordered list of
 * {@link Issue}s, each naming the exact Compose path it came from. Nothing is ignored anonymously —
 * a key this converter has never heard of still produces a WARNING rather than vanishing.
 *
 * <h2>Severity: warning or rejection</h2>
 * A warning nobody reads is only marginally better than silence, so the classification is by
 * <em>whether the resulting workload would still be the workload the user described</em>:
 *
 * <ul>
 *   <li>{@link Severity#ERROR} — the Deployment would run, but wrong: a different process
 *       ({@code command}, {@code entrypoint}), without its data ({@code volumes}, {@code tmpfs}),
 *       without its configuration ({@code env_file}), or with no image at all ({@code build}).
 *       Also anything requiring the server to read a local file it does not have
 *       ({@code configs.*.file}, {@code secrets.*.file}) — the previous code turned
 *       {@code configs: {x: {file: ./x.conf}}} into a ConfigMap whose data was the <em>filename</em>.
 *       Callers are expected to refuse to apply on an ERROR (see
 *       {@code WorkloadController.apply}); the escape hatch is explicit, never implicit.</li>
 *   <li>{@link Severity#WARNING} — the workload still does what it should, but something about
 *       <em>how</em> it is supervised is lost: no readiness probe ({@code healthcheck}), no start
 *       ordering ({@code depends_on}), Rigger's own network and labels instead of the file's.</li>
 *   <li>{@link Severity#INFO} — translated, but not literally: a published port turning the Service
 *       into a LoadBalancer, {@code expose} becoming a ClusterIP port, a resource limit being
 *       re-expressed, a name being rewritten to fit Rigger's name pattern.</li>
 * </ul>
 *
 * <h2>Mapping</h2>
 * <table><caption>Compose → Rigger</caption>
 *   <tr><td>{@code services.*}</td><td>{@link DeploymentSpec} (+ {@link ServiceSpec} when it
 *       exposes ports)</td></tr>
 *   <tr><td>{@code configs.*.content}</td><td>{@link ConfigMapSpec}</td></tr>
 *   <tr><td>{@code secrets.*}</td><td>{@link SecretSpec} — only when a value is inline; Compose has
 *       no inline secret value, so in practice these are always reported instead</td></tr>
 * </table>
 */
@Component
public class ComposeConverter {

    // ── report model ────────────────────────────────────────────────────────

    public enum Severity { ERROR, WARNING, INFO }

    /**
     * One thing the converter did not carry across literally.
     *
     * @param severity see the class javadoc for how ERROR/WARNING/INFO are decided.
     * @param path     the Compose path it came from, e.g. {@code services.web.volumes} — so the
     *                 report can be matched against the file line by line.
     * @param message  what happened and what the user can do instead.
     */
    public record Issue(
            @JsonProperty("severity") Severity severity,
            @JsonProperty("path") String path,
            @JsonProperty("message") String message) {

        @Override
        public String toString() {
            return "[" + severity + "] " + path + " — " + message;
        }
    }

    /** Conversion result: what Rigger will run, and what it will not. */
    public record Conversion(List<ParsedManifest> manifests, List<Issue> issues) {

        public List<Issue> errors() {
            return issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
        }

        public boolean hasErrors() {
            return !errors().isEmpty();
        }
    }

    // ── unsupported-key tables ──────────────────────────────────────────────

    /**
     * Service keys whose loss changes what the workload <em>is</em>. LinkedHashMap so the report
     * order is stable (tests and humans both rely on that).
     */
    private static final Map<String, String> SERVICE_BLOCKING = new LinkedHashMap<>();
    static {
        SERVICE_BLOCKING.put("volumes",
            "Rigger has no volume or bind-mount primitive — the container would start with none of "
            + "this data. Move the state to a managed database, or create the Swarm volume/mount "
            + "outside Rigger.");
        SERVICE_BLOCKING.put("volumes_from", "same as volumes: Rigger cannot express mounts.");
        SERVICE_BLOCKING.put("tmpfs", "same as volumes: Rigger cannot express mounts.");
        SERVICE_BLOCKING.put("build",
            "Rigger deploys images, it does not build them. Build and push the image, then set image:.");
        SERVICE_BLOCKING.put("command",
            "Rigger cannot override a container's command — the image's own entrypoint would run "
            + "instead, i.e. a different process from the one described here. Bake the command into "
            + "the image.");
        SERVICE_BLOCKING.put("entrypoint",
            "Rigger cannot override a container's entrypoint — see command:.");
        SERVICE_BLOCKING.put("env_file",
            "the server has no access to this file, so the variables would be missing entirely. "
            + "Inline them under environment:, or put them in a ConfigMap/Secret.");
    }

    /** Service keys whose loss changes only how the workload is supervised or connected. */
    private static final Map<String, String> SERVICE_WARNING = new LinkedHashMap<>();
    static {
        SERVICE_WARNING.put("healthcheck",
            "Rigger has no readiness/liveness probe, so a rolling update cannot wait for a task to "
            + "become healthy; it waits out strategy.delaySeconds instead.");
        SERVICE_WARNING.put("depends_on",
            "start ordering is not expressible; Swarm restarts failing tasks until dependencies "
            + "answer.");
        SERVICE_WARNING.put("restart",
            "Swarm's own restart policy applies; the value here is not carried over.");
        SERVICE_WARNING.put("networks",
            "Rigger attaches every service to the overlay network it manages; custom network "
            + "topology and aliases are dropped.");
        SERVICE_WARNING.put("network_mode", "not expressible — Rigger always uses its overlay network.");
        SERVICE_WARNING.put("labels",
            "container labels are not carried over; Rigger sets its own rigger.io/* labels.");
        SERVICE_WARNING.put("container_name",
            "Swarm names tasks itself; the resource is named after the Compose service key.");
        SERVICE_WARNING.put("user", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("working_dir", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("extra_hosts", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("dns", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("logging", "not expressible; Swarm's default log driver is used.");
        SERVICE_WARNING.put("privileged", "not expressible — the task runs unprivileged.");
        SERVICE_WARNING.put("cap_add", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("cap_drop", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("devices", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("security_opt", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("sysctls", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("ulimits", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("stop_grace_period", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("stop_signal", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("shm_size", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("init", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("tty", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("stdin_open", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("hostname", "Swarm assigns task hostnames.");
        SERVICE_WARNING.put("domainname", "Swarm assigns task hostnames.");
        SERVICE_WARNING.put("platform", "not expressible; placement is left to Swarm.");
        SERVICE_WARNING.put("profiles", "Compose profiles have no Rigger equivalent — the service is "
            + "always converted.");
        SERVICE_WARNING.put("links", "legacy Compose v1 key; use the overlay network's DNS names.");
        SERVICE_WARNING.put("external_links", "legacy Compose v1 key with no Rigger equivalent.");
        SERVICE_WARNING.put("cgroup_parent", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("pid", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("ipc", "not expressible in a Rigger Deployment.");
        SERVICE_WARNING.put("mem_limit",
            "legacy Compose v2 key; declare deploy.resources.limits.memory instead, which Rigger "
            + "does carry over.");
        SERVICE_WARNING.put("cpus",
            "legacy Compose v2 key; declare deploy.resources.limits.cpus instead, which Rigger "
            + "does carry over.");
    }

    /** Service keys the converter reads. Anything outside this set is reported, never dropped. */
    private static final Set<String> SERVICE_HANDLED =
        Set.of("image", "deploy", "environment", "ports", "expose", "configs", "secrets");

    /** {@code deploy.*} keys the converter reads. */
    private static final Set<String> DEPLOY_HANDLED =
        Set.of("replicas", "resources", "update_config");

    private static final Map<String, String> DEPLOY_WARNING = new LinkedHashMap<>();
    static {
        DEPLOY_WARNING.put("mode",
            "Rigger only models replicated services; a global service becomes a replicated one.");
        DEPLOY_WARNING.put("placement", "placement constraints/preferences are not expressible.");
        DEPLOY_WARNING.put("restart_policy", "Swarm's default restart policy applies.");
        DEPLOY_WARNING.put("rollback_config",
            "only strategy.failureAction is expressible; the rest of rollback_config is dropped.");
        DEPLOY_WARNING.put("endpoint_mode", "Rigger always uses Swarm's ingress (VIP) mode.");
        DEPLOY_WARNING.put("max_replicas_per_node", "not expressible in a Rigger Deployment.");
        DEPLOY_WARNING.put("labels", "service labels are not carried over; Rigger sets its own.");
    }

    private static final Set<String> ROOT_HANDLED = Set.of("services", "configs", "secrets");

    // Rigger resource names: ^[a-z0-9][a-z0-9\-]{0,61}[a-z0-9]$ (see the JSON Schemas).
    private static final int MAX_NAME = 63;

    // ── mappers ─────────────────────────────────────────────────────────────

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    /**
     * Serialises generated manifests back to YAML. Deliberately does <em>not</em> enable
     * MINIMIZE_QUOTES: an env value of {@code "3306"} or {@code "yes"} would come back unquoted and
     * re-parse as a number/boolean, so the round trip would change the value. Verbose quoting is the
     * price of {@code convert | apply} meaning exactly what it printed.
     */
    private final ObjectMapper yamlOut = new ObjectMapper(new YAMLFactory())
        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    // ── entry points ────────────────────────────────────────────────────────

    /** Converts a Compose file on disk. */
    public Conversion convert(Path composePath, String namespace) throws IOException {
        return convert(yaml.readTree(composePath.toFile()), namespace, composePath.toString());
    }

    /**
     * Converts Compose content already in memory — the apply and convert endpoints receive the file
     * as a string, so they never have a path to hand.
     */
    public Conversion convertString(String composeYaml, String namespace, String source)
            throws IOException {
        return convert(yaml.readTree(composeYaml), namespace, source);
    }

    /**
     * Whether the given YAML looks like a Compose file rather than a Rigger manifest.
     *
     * <p>Keyed on a top-level {@code services} map with no {@code apiVersion}/{@code kind}: a Rigger
     * manifest always carries both, and a Compose file never does. Returns false for anything
     * unparseable so the caller reports the real manifest error rather than a misleading
     * "not valid Compose".
     */
    public boolean isCompose(String content) {
        try {
            var root = yaml.readTree(content);
            return root != null
                && root.path("services").isObject()
                && root.path("apiVersion").isMissingNode()
                && root.path("kind").isMissingNode();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Renders converted manifests as a multi-document {@code rigger.io/v1} YAML string — the whole
     * point of {@code riggerctl convert}: the output has to be a file {@code riggerctl apply} accepts
     * and JSON-Schema validation passes.
     *
     * <p>Which is why the {@code Service.type} field is written in the Kubernetes spelling the
     * schemas declare ({@code ClusterIP}) rather than {@link ServiceType}'s constant name
     * ({@code CLUSTER_IP}): {@code ServiceType} has a lenient {@code @JsonCreator} but no
     * {@code @JsonValue}, so a straight serialisation parses fine and then fails schema validation.
     */
    public String toYaml(List<ParsedManifest> manifests) {
        var sb = new StringBuilder();
        for (var pm : manifests) {
            try {
                sb.append(yamlOut.writeValueAsString(toNode(pm.manifest())));
            } catch (Exception e) {
                throw new IllegalStateException("Could not render generated manifest as YAML", e);
            }
        }
        return sb.toString();
    }

    private ObjectNode toNode(RiggerManifest manifest) {
        var root = yamlOut.createObjectNode();
        root.put("apiVersion", manifest.apiVersion());
        root.put("kind", manifest.kind());
        root.set("metadata", yamlOut.valueToTree(manifest.metadata()));

        var spec = (ObjectNode) yamlOut.valueToTree(manifest.spec());
        if (manifest.spec() instanceof DeploymentSpec dep) {
            // NON_EMPTY drops a zero-replica Deployment's replicas; scaled-to-zero is a real state.
            spec.put("replicas", dep.replicas());
            // The default strategy is noise in generated output — it says nothing the reader
            // doesn't get from the absence of the key.
            if (RollingUpdateStrategy.DEFAULT.equals(dep.strategy())) spec.remove("strategy");
        }
        if (manifest.spec() instanceof ServiceSpec svc) {
            spec.put("type", svc.type() == ServiceType.LOAD_BALANCER ? "LoadBalancer" : "ClusterIP");
        }
        root.set("spec", spec);
        return root;
    }

    // ── conversion ──────────────────────────────────────────────────────────

    private Conversion convert(JsonNode root, String namespace, String source) {
        var out = new ArrayList<ParsedManifest>();
        var issues = new ArrayList<Issue>();

        convertServices(root.path("services"), namespace, source, out, issues);
        convertConfigs(root.path("configs"), namespace, source, out, issues);
        convertSecrets(root.path("secrets"), namespace, source, out, issues);
        reportRootKeys(root, issues);

        return new Conversion(List.copyOf(out), List.copyOf(issues));
    }

    private void reportRootKeys(JsonNode root, List<Issue> issues) {
        if (root == null || !root.isObject()) return;
        root.properties().forEach(e -> {
            String key = e.getKey();
            if (ROOT_HANDLED.contains(key) || key.startsWith("x-")) return;
            switch (key) {
                case "version" -> { /* obsolete and ignored by Compose itself — not worth a line. */ }
                case "volumes" -> issues.add(new Issue(Severity.ERROR, "volumes",
                    "Rigger has no volume primitive, so every mount referencing these volumes is "
                    + "lost. Create them outside Rigger if the workload needs them."));
                case "networks" -> issues.add(new Issue(Severity.WARNING, "networks",
                    "Rigger manages a single overlay network; declared networks are dropped."));
                case "name" -> issues.add(new Issue(Severity.WARNING, "name",
                    "the Compose project name has no Rigger equivalent — resources are named after "
                    + "their service key inside the target namespace."));
                default -> issues.add(new Issue(Severity.WARNING, key,
                    "top-level key not recognised by the converter; it was ignored."));
            }
        });
    }

    private void convertServices(JsonNode services, String ns, String source,
                                 List<ParsedManifest> out, List<Issue> issues) {
        if (services == null || !services.isObject()) return;

        services.properties().forEach(entry -> {
            String rawName = entry.getKey();
            JsonNode svc = entry.getValue();
            String path = "services." + rawName;

            String name = sanitizeName(rawName, path, issues);
            if (name == null) return;   // unusable name — already reported as an ERROR

            reportServiceKeys(svc, path, issues);

            String image = svc.path("image").asText(null);
            if (image == null || image.isBlank()) {
                issues.add(new Issue(Severity.ERROR, path + ".image",
                    "no image declared, and Rigger cannot build one. Set image: to something "
                    + "pullable."));
                return;
            }

            int replicas = svc.path("deploy").path("replicas").asInt(1);
            var env = convertEnvironment(svc.path("environment"), path, issues);
            var resources = convertResources(svc.path("deploy").path("resources"), path, issues);
            var strategy = convertUpdateConfig(svc.path("deploy").path("update_config"), path, issues);
            var configRefs = convertRefs(svc.path("configs"), path + ".configs", issues);
            var secretRefs = convertRefs(svc.path("secrets"), path + ".secrets", issues);

            var selector = Map.of("app", name);
            var spec = new DeploymentSpec(replicas, selector, image, env, resources, strategy,
                    null, configRefs, secretRefs);
            var meta = new ObjectMeta(name, ns, selector, Map.of());
            out.add(new ParsedManifest(
                new RiggerManifest(RiggerManifest.API_VERSION, "Deployment", meta, spec), source, null));

            var ports = convertPorts(svc.path("ports"), path, issues);
            boolean published = ports.stream().anyMatch(PortMapping::published);
            convertExpose(svc.path("expose"), path, ports, published, issues);
            if (ports.isEmpty()) return;

            // A Compose port with a host side means "reachable from outside the cluster", which is
            // exactly a LoadBalancer Service in Rigger. The old converter always produced ClusterIP
            // and discarded the host port, so `8080:80` became unreachable with no explanation.
            var type = published ? ServiceType.LOAD_BALANCER : ServiceType.CLUSTER_IP;
            if (published) {
                issues.add(new Issue(Severity.INFO, path + ".ports",
                    "a published host port makes this a LoadBalancer Service — the port is published "
                    + "on every Swarm node, not only on the host that ran docker compose."));
                // A Rigger Service carries one type for all its ports, so an expose-only port ends
                // up published alongside the real ones — the opposite of what expose: means. Said
                // out loud rather than left for someone to discover with a port scanner.
                if (ports.stream().anyMatch(PortMapping::fromExpose)) {
                    issues.add(new Issue(Severity.WARNING, path + ".expose",
                        "this service also publishes a host port, and a Rigger Service has one type "
                        + "for all of its ports — so the expose: port is published externally too. "
                        + "Remove it from the generated Service if it must stay internal."));
                }
            }
            var svcSpec = new ServiceSpec(selector,
                ports.stream().map(PortMapping::toServicePort).toList(), type);
            var svcMeta = new ObjectMeta(serviceName(name), ns, selector, Map.of());
            out.add(new ParsedManifest(
                new RiggerManifest(RiggerManifest.API_VERSION, "Service", svcMeta, svcSpec), source, null));
        });
    }

    private void reportServiceKeys(JsonNode svc, String path, List<Issue> issues) {
        if (!svc.isObject()) return;
        svc.properties().forEach(e -> {
            String key = e.getKey();
            if (SERVICE_HANDLED.contains(key) || key.startsWith("x-")) return;
            String blocking = SERVICE_BLOCKING.get(key);
            if (blocking != null) {
                issues.add(new Issue(Severity.ERROR, path + "." + key, blocking));
                return;
            }
            String warning = SERVICE_WARNING.get(key);
            issues.add(new Issue(Severity.WARNING, path + "." + key,
                warning != null ? warning
                    : "key not recognised by the converter; it was ignored."));
        });

        var deploy = svc.path("deploy");
        if (!deploy.isObject()) return;
        deploy.properties().forEach(e -> {
            String key = e.getKey();
            if (DEPLOY_HANDLED.contains(key) || key.startsWith("x-")) return;
            String warning = DEPLOY_WARNING.get(key);
            issues.add(new Issue(Severity.WARNING, path + ".deploy." + key,
                warning != null ? warning
                    : "deploy key not recognised by the converter; it was ignored."));
        });
    }

    // ── environment ─────────────────────────────────────────────────────────

    /**
     * Reads both Compose spellings of {@code environment}: the map form and the
     * {@code - KEY=value} list form. The list form used to yield zero variables — the code called
     * {@code properties()} on an array node, which is empty — so a perfectly ordinary Compose file
     * produced a container with no configuration at all and no complaint.
     */
    private List<EnvVar> convertEnvironment(JsonNode node, String path, List<Issue> issues) {
        var env = new ArrayList<EnvVar>();
        if (node == null || node.isMissingNode() || node.isNull()) return env;
        String envPath = path + ".environment";

        if (node.isObject()) {
            node.properties().forEach(e -> {
                if (e.getValue().isNull()) {
                    issues.add(new Issue(Severity.WARNING, envPath + "." + e.getKey(),
                        "no value given, so Compose would pass it through from the host "
                        + "environment; the server has no such environment. Variable skipped."));
                    return;
                }
                addEnv(env, e.getKey(), e.getValue().asText(), envPath, issues);
            });
        } else if (node.isArray()) {
            node.forEach(item -> {
                String raw = item.asText();
                int eq = raw.indexOf('=');
                if (eq < 0) {
                    issues.add(new Issue(Severity.WARNING, envPath + "." + raw,
                        "declared without a value, so Compose would pass it through from the host "
                        + "environment; the server has no such environment. Variable skipped."));
                    return;
                }
                addEnv(env, raw.substring(0, eq), raw.substring(eq + 1), envPath, issues);
            });
        } else {
            issues.add(new Issue(Severity.WARNING, envPath,
                "expected a map or a list; value ignored."));
        }
        return env;
    }

    private void addEnv(List<EnvVar> env, String key, String value, String envPath, List<Issue> issues) {
        if (key.isBlank()) return;
        if (value.contains("${")) {
            // Compose substitutes from the shell/.env at `docker compose up` time. Rigger applies a
            // manifest server-side, where neither exists, so the literal text is what would run.
            issues.add(new Issue(Severity.WARNING, envPath + "." + key,
                "contains a ${...} reference; Rigger does not perform Compose variable "
                + "interpolation, so the literal text is kept."));
        }
        env.add(new EnvVar(key, value, null));
    }

    // ── ports ───────────────────────────────────────────────────────────────

    /**
     * A resolved port mapping. {@code published} distinguishes a host port from a bare target;
     * {@code fromExpose} marks the ones that came from {@code expose:} rather than {@code ports:},
     * which matters because a Rigger Service has a single type for all of its ports.
     */
    private record PortMapping(int port, int targetPort, String protocol, boolean published,
                               boolean fromExpose) {
        PortMapping(int port, int targetPort, String protocol, boolean published) {
            this(port, targetPort, protocol, published, false);
        }

        ServicePort toServicePort() {
            return new ServicePort(port, targetPort, protocol);
        }
    }

    /**
     * Reads every Compose {@code ports} spelling: {@code 80}, {@code "8080:80"},
     * {@code "8080:80/udp"}, {@code "127.0.0.1:8080:80"} and the long form
     * {@code {target: 80, published: 8080, protocol: udp}}.
     *
     * <p>The long form used to throw {@link NumberFormatException} on {@code asText()} of an object
     * node and land in an empty catch block — the port simply disappeared. The host port was
     * discarded too, and {@code /udp} was coerced to TCP.
     */
    private List<PortMapping> convertPorts(JsonNode node, String path, List<Issue> issues) {
        var ports = new ArrayList<PortMapping>();
        if (node == null || node.isMissingNode() || node.isNull()) return ports;
        String portsPath = path + ".ports";
        if (!node.isArray()) {
            issues.add(new Issue(Severity.WARNING, portsPath, "expected a list; value ignored."));
            return ports;
        }

        node.forEach(p -> {
            if (p.isObject()) {
                if (p.hasNonNull("mode") && "host".equals(p.path("mode").asText())) {
                    issues.add(new Issue(Severity.WARNING, portsPath + ".mode",
                        "host publishing mode is not expressible; Swarm's ingress (routing mesh) "
                        + "mode is used instead."));
                }
                int target = p.path("target").asInt(0);
                if (target <= 0) {
                    issues.add(new Issue(Severity.WARNING, portsPath,
                        "long-form entry without a usable target port; entry ignored."));
                    return;
                }
                String protocol = protocolOf(p.path("protocol").asText("tcp"));
                var publishedNode = p.path("published");
                if (publishedNode.isMissingNode() || publishedNode.isNull()) {
                    ports.add(new PortMapping(target, target, protocol, false));
                    return;
                }
                Integer host = parsePort(publishedNode.asText(), portsPath, issues);
                if (host == null) return;
                ports.add(new PortMapping(host, target, protocol, true));
                return;
            }

            String raw = p.asText();
            if (raw.isBlank()) return;
            String protocol = "TCP";
            int slash = raw.indexOf('/');
            if (slash >= 0) {
                protocol = protocolOf(raw.substring(slash + 1));
                raw = raw.substring(0, slash);
            }
            var parts = raw.split(":");
            String targetPart = parts[parts.length - 1];
            if (targetPart.contains("-") || (parts.length > 1 && parts[parts.length - 2].contains("-"))) {
                issues.add(new Issue(Severity.WARNING, portsPath,
                    "port range '" + raw + "' is not expressible; list the ports individually. "
                    + "Entry ignored."));
                return;
            }
            Integer target = parsePort(targetPart, portsPath, issues);
            if (target == null) return;
            if (parts.length == 1) {
                // Compose would pick an ephemeral host port. Nothing to publish deterministically.
                ports.add(new PortMapping(target, target, protocol, false));
                return;
            }
            Integer host = parsePort(parts[parts.length - 2], portsPath, issues);
            if (host == null) return;
            if (parts.length > 2) {
                issues.add(new Issue(Severity.WARNING, portsPath,
                    "host IP '" + parts[0] + "' cannot be honoured — Swarm publishes on every node."));
            }
            ports.add(new PortMapping(host, target, protocol, true));
        });
        return ports;
    }

    /**
     * {@code expose} is internal-only, which is exactly a ClusterIP port — as long as the service
     * publishes nothing else. {@code published} is passed in so the report doesn't promise
     * "internal only" for a Service that the very next issue says is a LoadBalancer.
     */
    private void convertExpose(JsonNode node, String path, List<PortMapping> ports,
                               boolean published, List<Issue> issues) {
        if (node == null || !node.isArray()) return;
        node.forEach(p -> {
            String raw = p.asText();
            String protocol = "TCP";
            int slash = raw.indexOf('/');
            if (slash >= 0) {
                protocol = protocolOf(raw.substring(slash + 1));
                raw = raw.substring(0, slash);
            }
            Integer port = parsePort(raw, path + ".expose", issues);
            if (port == null) return;
            if (ports.stream().anyMatch(m -> m.targetPort() == port)) return;   // already published
            ports.add(new PortMapping(port, port, protocol, false, true));
            issues.add(new Issue(Severity.INFO, path + ".expose",
                published
                    ? "port " + port + " became a Service port."
                    : "port " + port + " became a ClusterIP Service port — reachable inside the "
                      + "cluster only, matching expose: semantics."));
        });
    }

    private Integer parsePort(String raw, String path, List<Issue> issues) {
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException(raw);
            return port;
        } catch (NumberFormatException e) {
            issues.add(new Issue(Severity.WARNING, path,
                "'" + raw + "' is not a port number in 1..65535; entry ignored."));
            return null;
        }
    }

    private String protocolOf(String raw) {
        return "udp".equalsIgnoreCase(raw.trim()) ? "UDP" : "TCP";
    }

    // ── deploy.resources / update_config ────────────────────────────────────

    private ResourceRequirements convertResources(JsonNode node, String path, List<Issue> issues) {
        if (node == null || !node.isObject()) return null;
        String base = path + ".deploy.resources";
        var limits = node.path("limits");
        var reservations = node.path("reservations");
        String cpuLimit = limits.path("cpus").isMissingNode() ? null : limits.path("cpus").asText();
        String memLimit = normalizeMemory(limits.path("memory"), base + ".limits.memory", issues);
        String cpuReserved = reservations.path("cpus").isMissingNode() ? null : reservations.path("cpus").asText();
        String memReserved = normalizeMemory(reservations.path("memory"), base + ".reservations.memory", issues);

        limits.properties().forEach(e -> {
            if (!Set.of("cpus", "memory").contains(e.getKey()))
                issues.add(new Issue(Severity.WARNING, base + ".limits." + e.getKey(),
                    "not expressible in a Rigger Deployment."));
        });
        reservations.properties().forEach(e -> {
            if (!Set.of("cpus", "memory").contains(e.getKey()))
                issues.add(new Issue(Severity.WARNING, base + ".reservations." + e.getKey(),
                    "not expressible in a Rigger Deployment."));
        });

        if (cpuLimit == null && memLimit == null && cpuReserved == null && memReserved == null) return null;
        issues.add(new Issue(Severity.INFO, base,
            "carried over as spec.resources (limits → cpuLimit/memoryLimit, reservations → "
            + "cpuReserved/memoryReserved)."));
        return new ResourceRequirements(cpuLimit, memLimit, cpuReserved, memReserved);
    }

    /**
     * Rewrites Compose's memory spelling ({@code 128M}, {@code 1G}, {@code 512kb}) into the one
     * {@code MemoryUnit.toBytes} understands ({@code Ki}/{@code Mi}/{@code Gi}/{@code Ti}, lowercase
     * {@code m}/{@code g}, or bytes).
     *
     * <p>Found the hard way, and exactly the kind of defect this project keeps warning about: passing
     * {@code 128M} straight through produced a manifest that applied, validated and stored fine, then
     * failed forever inside the reconciliation loop — {@code Long.parseLong("128M")} throws, so
     * {@code ServiceAdapter.create} threw on every cycle and the Deployment simply never appeared in
     * Swarm. An unparseable value is dropped with a WARNING instead, because a Deployment running
     * without a memory limit is recoverable and one that never starts is not.
     */
    private String normalizeMemory(JsonNode node, String path, List<Issue> issues) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String raw = node.asText().trim();
        if (raw.isEmpty()) return null;
        var matcher = java.util.regex.Pattern
            .compile("^(\\d+(?:\\.\\d+)?)\\s*([kmgt]i?b?|b)?$", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(raw);
        if (!matcher.matches()) {
            issues.add(new Issue(Severity.WARNING, path,
                "'" + raw + "' is not a memory size Rigger can parse; the limit was dropped rather "
                + "than fail reconciliation. Use e.g. 128Mi."));
            return null;
        }
        double amount = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2) == null ? "b" : matcher.group(2).toLowerCase(Locale.ROOT);
        long multiplier = switch (unit.charAt(0)) {
            case 'k' -> 1024L;
            case 'm' -> 1024L * 1024L;
            case 'g' -> 1024L * 1024L * 1024L;
            case 't' -> 1024L * 1024L * 1024L * 1024L;
            default -> 1L;
        };
        long bytes = Math.round(amount * multiplier);
        // Whole binary units where possible (readable), plain bytes otherwise (always parseable).
        if (bytes % (1024L * 1024L * 1024L) == 0) return (bytes / (1024L * 1024L * 1024L)) + "Gi";
        if (bytes % (1024L * 1024L) == 0) return (bytes / (1024L * 1024L)) + "Mi";
        if (bytes % 1024L == 0) return (bytes / 1024L) + "Ki";
        return String.valueOf(bytes);
    }

    private RollingUpdateStrategy convertUpdateConfig(JsonNode node, String path, List<Issue> issues) {
        if (node == null || !node.isObject()) return null;
        String base = path + ".deploy.update_config";
        int parallelism = node.path("parallelism").asInt(1);
        int delay = (int) Math.round(parseDuration(node.path("delay").asText(null)));
        String failureAction = switch (node.path("failure_action").asText("pause").toLowerCase()) {
            case "rollback" -> "ROLLBACK";
            case "continue" -> "CONTINUE";
            default -> "PAUSE";
        };
        node.properties().forEach(e -> {
            if (!Set.of("parallelism", "delay", "failure_action").contains(e.getKey()))
                issues.add(new Issue(Severity.WARNING, base + "." + e.getKey(),
                    "not expressible in a Rigger rolling-update strategy."));
        });
        issues.add(new Issue(Severity.INFO, base,
            "carried over as spec.strategy (parallelism → maxUnavailable, delay → delaySeconds, "
            + "failure_action → failureAction)."));
        return new RollingUpdateStrategy(parallelism, delay, failureAction);
    }

    /** Compose durations look like {@code 10s} / {@code 1m30s}; Rigger only has whole seconds. */
    private double parseDuration(String raw) {
        if (raw == null || raw.isBlank()) return 10;
        double total = 0;
        var number = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (Character.isDigit(c) || c == '.') { number.append(c); continue; }
            double value = number.isEmpty() ? 0 : Double.parseDouble(number.toString());
            number.setLength(0);
            total += switch (c) {
                case 'h' -> value * 3600;
                case 'm' -> value * 60;
                case 's' -> value;
                default -> 0;
            };
        }
        if (!number.isEmpty()) total += Double.parseDouble(number.toString());
        return total;
    }

    // ── service-level configs / secrets ─────────────────────────────────────

    /**
     * Service-level {@code configs:}/{@code secrets:}, short ({@code - name}) or long
     * ({@code - source: name, target: /path}) form, become {@code configMapRefs}/{@code secretRefs}.
     * Neither was read before, so a Compose file that mounted a config got a Deployment with no
     * reference to it.
     */
    private List<String> convertRefs(JsonNode node, String path, List<Issue> issues) {
        var refs = new ArrayList<String>();
        if (node == null || !node.isArray()) return refs;
        node.forEach(item -> {
            if (item.isObject()) {
                String sourceRef = item.path("source").asText(null);
                if (sourceRef == null) {
                    issues.add(new Issue(Severity.WARNING, path,
                        "long-form entry without source:; entry ignored."));
                    return;
                }
                item.properties().forEach(e -> {
                    if (!"source".equals(e.getKey()))
                        issues.add(new Issue(Severity.WARNING, path + "." + e.getKey(),
                            "Rigger decides where a ConfigMap/Secret is injected; this is dropped."));
                });
                refs.add(sourceRef);
            } else {
                refs.add(item.asText());
            }
        });
        return refs;
    }

    // ── top-level configs / secrets ─────────────────────────────────────────

    private void convertConfigs(JsonNode configs, String ns, String source,
                                List<ParsedManifest> out, List<Issue> issues) {
        if (configs == null || !configs.isObject()) return;
        configs.properties().forEach(entry -> {
            String rawName = entry.getKey();
            JsonNode node = entry.getValue();
            String path = "configs." + rawName;
            String name = sanitizeName(rawName, path, issues);
            if (name == null) return;

            if (node.path("external").asBoolean(false)) {
                issues.add(new Issue(Severity.WARNING, path,
                    "external config: assumed to exist in Swarm already; no ConfigMap generated."));
                return;
            }
            // Compose's own inline spelling. The only form whose *content* is actually present.
            String content = node.path("content").asText(null);
            if (content == null) {
                issues.add(new Issue(Severity.ERROR, path,
                    node.hasNonNull("file")
                        ? "declared from file '" + node.path("file").asText() + "', which the server "
                          + "cannot read — the previous converter stored the file *name* as the "
                          + "config value. Inline the contents under content:, or apply a ConfigMap "
                          + "manifest separately."
                        : "no content: given, so there is nothing to put in a ConfigMap."));
                return;
            }
            var spec = new ConfigMapSpec(Map.of(rawName, content));
            var meta = new ObjectMeta(name, ns, Map.of(), Map.of());
            out.add(new ParsedManifest(
                new RiggerManifest(RiggerManifest.API_VERSION, "ConfigMap", meta, spec), source, null));
            issues.add(new Issue(Severity.INFO, path,
                "content stored under the key '" + rawName + "' of ConfigMap '" + name + "'."));
        });
    }

    private void convertSecrets(JsonNode secrets, String ns, String source,
                                List<ParsedManifest> out, List<Issue> issues) {
        if (secrets == null || !secrets.isObject()) return;
        secrets.properties().forEach(entry -> {
            String rawName = entry.getKey();
            JsonNode node = entry.getValue();
            String path = "secrets." + rawName;
            String name = sanitizeName(rawName, path, issues);
            if (name == null) return;

            if (node.path("external").asBoolean(false)) {
                issues.add(new Issue(Severity.WARNING, path,
                    "external secret: assumed to exist in Swarm already; no Secret generated."));
                return;
            }
            if (node.hasNonNull("environment")) {
                issues.add(new Issue(Severity.WARNING, path,
                    "value comes from the host environment variable '"
                    + node.path("environment").asText() + "', which the server cannot read. "
                    + "Apply a Secret manifest with the base64 value instead."));
                return;
            }
            // Compose has no inline secret value at all; `file:` is the usual form and the server
            // has no access to it. Rigger Secret values are base64 in the manifest, so the user has
            // to supply them — but they must be *told*, which is the whole point of this branch.
            issues.add(new Issue(Severity.ERROR, path,
                node.hasNonNull("file")
                    ? "declared from file '" + node.path("file").asText() + "', which the server "
                      + "cannot read. Apply a Secret manifest whose data values are base64-encoded."
                    : "no value the server can read. Apply a Secret manifest whose data values are "
                      + "base64-encoded."));
        });
    }

    // ── names ───────────────────────────────────────────────────────────────

    /**
     * Compose keys are freer than Rigger resource names (which the JSON Schemas pin to
     * {@code ^[a-z0-9][a-z0-9\-]{0,61}[a-z0-9]$}), so {@code Web_App} has to be rewritten. Before,
     * nothing checked: the name went straight through, apply skipped schema validation for converted
     * manifests, and the same file then failed the moment anyone applied the converted YAML.
     *
     * @return the usable name, or {@code null} when there is none (reported as an ERROR).
     */
    private String sanitizeName(String raw, String path, List<Issue> issues) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
        if (cleaned.length() > MAX_NAME) cleaned = cleaned.substring(0, MAX_NAME).replaceAll("-+$", "");
        if (cleaned.length() < 2) {
            issues.add(new Issue(Severity.ERROR, path,
                "'" + raw + "' cannot be expressed as a Rigger resource name (needs 2-63 chars of "
                + "a-z, 0-9 and '-'). Rename it in the Compose file."));
            return null;
        }
        if (!cleaned.equals(raw)) {
            issues.add(new Issue(Severity.INFO, path,
                "renamed to '" + cleaned + "' to satisfy Rigger's resource-name pattern."));
        }
        return cleaned;
    }

    /** {@code <name>-svc}, trimmed so the Service name still fits the name pattern. */
    private String serviceName(String name) {
        String candidate = name + "-svc";
        if (candidate.length() <= MAX_NAME) return candidate;
        return name.substring(0, MAX_NAME - 4).replaceAll("-+$", "") + "-svc";
    }
}
