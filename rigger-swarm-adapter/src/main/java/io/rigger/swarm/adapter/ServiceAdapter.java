package io.rigger.swarm.adapter;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.api.command.CreateServiceCmd;
import io.rigger.core.domain.resource.*;
import io.rigger.core.util.MemoryUnit;
import io.rigger.swarm.client.DockerApiException;
import io.rigger.swarm.client.DockerClientFactory;
import io.rigger.swarm.config.IngressProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Translates Rigger Deployment resources to Docker Swarm services using docker-java.
 *
 * <p>docker-java handles all filter serialisation correctly — no manual URL encoding.
 * Filters are passed as typed objects and docker-java builds the correct JSON format
 * ({"label": ["key=value"]}) before sending to the Docker API.
 */
@Component
public class ServiceAdapter {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdapter.class);
    private static final String LABEL_NAMESPACE = "rigger.io/namespace";
    private static final String LABEL_NAME      = "rigger.io/name";
    private static final String LABEL_KIND      = "rigger.io/kind";
    /**
     * Marks a Swarm service as owned by the reconciliation loop.
     *
     * <p><strong>Never put this label on infrastructure Rigger deploys for itself</strong> (the
     * Traefik ingress controller, above all). {@link #listManaged()} feeds
     * {@code DeploymentController.reconcile()}, which deletes every managed service that has no
     * matching row in the {@code resources} table — so a labelled Traefik would be garbage-collected
     * within one 15s cycle of being created, forever. Infrastructure carries
     * {@code rigger.io/component} instead, which this filter never sees.
     */
    private static final String LABEL_MANAGED   = "rigger.io/managed";
    private static final String LABEL_SPEC_HASH = "rigger.io/spec-hash";

    private final DockerClientFactory factory;
    private final ConfigAdapter configAdapter;
    private final NetworkAdapter networks;
    private final IngressProperties ingressProps;

    public ServiceAdapter(DockerClientFactory factory, ConfigAdapter configAdapter,
                          NetworkAdapter networks, IngressProperties ingressProps) {
        this.factory = factory;
        this.configAdapter = configAdapter;
        this.networks = networks;
        this.ingressProps = ingressProps;
    }

    private DockerClient docker() { return factory.get(); }

    /**
     * Lists all Swarm services managed by Rigger.
     * docker-java correctly serialises the label filter as:
     *   {"label": ["rigger.io/managed=true"]}
     */
    public List<Service> listManaged() {
        try {
            return docker().listServicesCmd()
                .withLabelFilter(Map.of("rigger.io/managed", "true"))
                .exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to list managed services", e);
        }
    }

    /**
     * Finds a Rigger-managed service by namespace and name.
     */
    public Optional<Service> find(String namespace, String name) {
        try {
            return docker().listServicesCmd()
                .withLabelFilter(Map.of(
                    LABEL_NAMESPACE, namespace,
                    LABEL_NAME, name
                ))
                .exec()
                .stream()
                .findFirst();
        } catch (Exception e) {
            throw new DockerApiException("Failed to find service " + namespace + "/" + name, e);
        }
    }

    /**
     * Creates a new Swarm service from a Rigger Deployment spec.
     *
     * @param binding the Rigger Service exposing this Deployment, or null when none does. Must be
     *                the exact same value that was passed to {@link #computeSpecHash} for this
     *                Deployment in this cycle.
     */
    public String create(ObjectMeta meta, DeploymentSpec spec, ServiceBinding binding) {
        log.info("Creating Swarm service for Deployment: {}/{}", meta.namespace(), meta.name());
        try {
            var labels = buildLabels(meta, spec, binding);
            var serviceSpec = buildServiceSpec(meta, spec, labels, binding);

            var response = docker().createServiceCmd(serviceSpec).exec();
            log.info("Service created: {}/{} -> swarm id={}", meta.namespace(), meta.name(), response.getId());
            return response.getId();
        } catch (Exception e) {
            throw new DockerApiException("Failed to create service " + meta.qualifiedName(), e);
        }
    }

    /**
     * Updates an existing Swarm service to match the new spec.
     * Uses the service's current version for optimistic concurrency.
     */
    public void update(Service existing, ObjectMeta meta, DeploymentSpec spec, ServiceBinding binding) {
        log.info("Updating Swarm service: {}/{}", meta.namespace(), meta.name());
        try {
            var labels  = buildLabels(meta, spec, binding);
            var newSpec = buildServiceSpec(meta, spec, labels, binding);
            long version = existing.getVersion() != null ? existing.getVersion().getIndex() : 0L;

            // NOTE: the EndpointSpec is NOT carried over from `existing` any more. It used to be,
            // because ServiceController was a second writer that published ports behind this
            // method's back — two writers racing on one Swarm service, each overwriting the other's
            // work every cycle. Ports (and Traefik labels, and the ingress network attachment) now
            // originate inside buildServiceSpec from `binding`, making this the single writer.
            // Re-adding the graft would resurrect deleted ports forever: once published, no spec
            // that omits them could ever take them away.

            docker().updateServiceCmd(existing.getId(), newSpec)
                .withVersion(version)
                .exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to update service " + meta.qualifiedName(), e);
        }
    }

    /**
     * Scales a Swarm service to the desired replica count.
     */
    public void scale(String serviceId, long currentVersion, int replicas) {
        log.info("Scaling service {} to {} replicas", serviceId, replicas);
        try {
            var services = docker().listServicesCmd()
                .withIdFilter(List.of(serviceId))
                .exec();
            if (services.isEmpty()) {
                throw new DockerApiException("Service not found: " + serviceId);
            }
            var svc  = services.get(0);
            var spec = svc.getSpec();
            if (spec == null) return;

            // Patch replica count
            var mode = spec.getMode();
            if (mode != null && mode.getReplicated() != null) {
                mode.getReplicated().withReplicas(replicas);
            }

            long version = svc.getVersion() != null ? svc.getVersion().getIndex() : currentVersion;
            docker().updateServiceCmd(serviceId, spec).withVersion(version).exec();
        } catch (DockerApiException e) {
            throw e;
        } catch (Exception e) {
            throw new DockerApiException("Failed to scale service " + serviceId, e);
        }
    }

    /**
     * Removes a Swarm service by ID.
     */
    public void delete(String serviceId) {
        log.info("Deleting Swarm service: {}", serviceId);
        try {
            docker().removeServiceCmd(serviceId).exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to delete service " + serviceId, e);
        }
    }

    /**
     * Lists all running tasks for a given service.
     */
    public List<Task> listTasks(String serviceId) {
        try {
            return docker().listTasksCmd()
                .withServiceFilter(serviceId)
                .exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to list tasks for service " + serviceId, e);
        }
    }

    /**
     * Streams a container's stdout/stderr to the given output stream.
     * Blocks until the log stream completes — forever if {@code follow} is true and the
     * container keeps running, so callers should run this on a request thread they're
     * prepared to hold open (e.g. a Spring {@code StreamingResponseBody}).
     */
    public void streamLogs(String containerId, boolean follow, java.io.OutputStream out) {
        try {
            var callback = new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    try {
                        out.write(frame.getPayload());
                        out.flush();
                    } catch (java.io.IOException e) {
                        onError(e);
                    }
                }
            };
            docker().logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(follow)
                .withTail(200)
                .exec(callback)
                .awaitCompletion();
        } catch (Exception e) {
            throw new DockerApiException("Failed to stream logs for container " + containerId, e);
        }
    }

    // ── private builders ──────────────────────────────────────────────────

    /**
     * Rigger's own labels and Traefik's are applied <em>after</em> the user's {@code metadata.labels}
     * so they always win. The other way round (which is how this started) let a manifest label named
     * {@code rigger.io/spec-hash} overwrite the real hash and freeze reconciliation for that
     * Deployment, or a hand-written {@code traefik.*} label silently redirect someone else's host.
     */
    private Map<String, String> buildLabels(ObjectMeta meta, DeploymentSpec spec, ServiceBinding binding) {
        var labels = new LinkedHashMap<String, String>();
        if (meta.labels() != null) labels.putAll(meta.labels());
        labels.put(LABEL_NAMESPACE, meta.namespace());
        labels.put(LABEL_NAME,      meta.name());
        labels.put(LABEL_KIND,      "Deployment");
        labels.put(LABEL_MANAGED,   "true");
        labels.put(LABEL_SPEC_HASH, computeSpecHash(meta, spec, binding));

        var ingress = ingressDecision(binding);
        if (ingress != null) {
            labels.putAll(TraefikLabels.forBinding(binding, ingressProps.getNetwork(), ingressProps));
        }
        return labels;
    }

    /**
     * Computes the {@code spec-hash} label value for a Deployment: the spec's own hash, folded with
     * the resolved {@code configMapRefs} Config IDs (so a ConfigMap content change, which doesn't
     * touch the Deployment spec itself, still changes this value) and with the resolved
     * {@link ServiceBinding} (so a Service's ports or ingress changing, or the cluster's ingress
     * configuration changing, does too).
     *
     * <p>{@code ResourceDiffer} (in rigger-operator) must use this exact same computation to decide
     * whether a Deployment needs updating — otherwise the value it compares against will never match
     * what actually gets written here, and reconciliation updates the Swarm service on every single
     * cycle forever instead of converging. There is deliberately no second entry point to this
     * function: a divergent copy is precisely how that bug shipped once already.
     *
     * <p>Equally important in the other direction: everything folded in here must be
     * <strong>stable</strong> across cycles. Map iteration order, a nondeterministic tie-break
     * between two Services selecting the same Deployment, or a value re-read from Docker that varies
     * would make the hash change on its own and the Swarm version index climb without end. Both
     * failure modes pass the other's test, so verify convergence and propagation separately.
     */
    public String computeSpecHash(ObjectMeta meta, DeploymentSpec spec, ServiceBinding binding) {
        var sb = new StringBuilder(Integer.toHexString(spec.hashCode()));

        var resolvedConfigs = resolveConfigs(meta.namespace(), spec);
        if (!resolvedConfigs.isEmpty()) {
            String configsSignature = resolvedConfigs.stream()
                .map(ContainerSpecConfig::getConfigID)
                .sorted()
                .collect(Collectors.joining(","));
            sb.append('-').append(Integer.toHexString(configsSignature.hashCode()));
        }

        // Absent binding appends nothing at all, so Deployments with no Service keep exactly the
        // hash they had before ingress existed and don't all churn once on upgrade.
        if (binding != null) {
            sb.append('-').append(Integer.toHexString(bindingSignature(binding).hashCode()));
        }
        return sb.toString();
    }

    /** Everything about a binding that changes the emitted Swarm spec, as a stable string. */
    private String bindingSignature(ServiceBinding binding) {
        var ingress = ingressDecision(binding);
        String ingressPart = ingress == null
            ? "noing"
            : binding.ingressSignature() + "|" + ingressProps.signature() + "|" + ingress;
        return binding.portsSignature() + "|" + ingressPart;
    }

    /**
     * Returns the ingress network ID to attach to, or null when this Deployment gets no ingress —
     * either because the feature is off, the binding declares none, or the overlay network isn't
     * there yet (first cycle after enabling; the next cycle picks it up).
     *
     * <p>Lookup-only: {@code NetworkAdapter.resolveId} never creates the network, because this runs
     * inside the spec-hash computation.
     */
    private String ingressDecision(ServiceBinding binding) {
        if (binding == null || !binding.hasIngress() || !ingressProps.isEnabled()) return null;
        String networkId = networks.resolveId(ingressProps.getNetwork());
        if (networkId == null) {
            log.warn("Ingress requested for {}/{} but overlay network '{}' does not exist yet — "
                   + "skipping Traefik labels this cycle", binding.serviceNamespace(),
                     binding.serviceName(), ingressProps.getNetwork());
        }
        return networkId;
    }

    /** Resolves configMapRefs to the currently live Docker Config for each name, skipping unresolved ones. */
    private List<ContainerSpecConfig> resolveConfigs(String namespace, DeploymentSpec spec) {
        var refs = new ArrayList<ContainerSpecConfig>();
        for (String ref : spec.configMapRefs()) {
            configAdapter.find(namespace, ref).ifPresent(cfg -> refs.add(new ContainerSpecConfig()
                .withConfigID(cfg.getId())
                .withConfigName(cfg.getSpec().getName())
                .withFile(new ContainerSpecFile().withName("/configmap/" + ref).withUid("0").withGid("0").withMode(0444L))));
        }
        return refs;
    }

    /**
     * Rebuilds the complete docker-java ServiceSpec. {@link #update} calls this and sends the result
     * as-is, so <strong>anything not produced in here is erased from the Swarm service</strong> on
     * the next Deployment update — published ports, Traefik labels and the ingress network
     * attachment all have to originate here rather than being patched in afterwards.
     */
    private com.github.dockerjava.api.model.ServiceSpec buildServiceSpec(
            ObjectMeta meta, DeploymentSpec spec, Map<String, String> labels, ServiceBinding binding) {
        // Container spec
        var containerSpec = new ContainerSpec()
            .withImage(spec.image());

        if (spec.env() != null) {
            var envList = spec.env().stream()
                .filter(e -> e.value() != null)
                .map(e -> e.name() + "=" + e.value())
                .collect(Collectors.toList());
            containerSpec.withEnv(envList);
        }

        var resolvedConfigs = resolveConfigs(meta.namespace(), spec);
        if (!resolvedConfigs.isEmpty()) {
            containerSpec.withConfigs(resolvedConfigs);
        }

        // Task template
        var taskTemplate = new TaskSpec()
            .withContainerSpec(containerSpec);

        // Resource limits
        if (spec.resources() != null && spec.resources().cpuLimit() != null) {
            var limits = new ResourceSpecs();
            try {
                long nanoCpu = (long)(Double.parseDouble(spec.resources().cpuLimit()) * 1_000_000_000L);
                limits.withNanoCPUs(nanoCpu);
            } catch (NumberFormatException ignored) {}
            if (spec.resources().memoryLimit() != null) {
                limits.withMemoryBytes(MemoryUnit.toBytes(spec.resources().memoryLimit()));
            }
            taskTemplate.withResources(new com.github.dockerjava.api.model.ResourceRequirements().withLimits(limits));
        }

        // Update config (rolling strategy)
        var strategy = spec.strategy() != null ? spec.strategy() : RollingUpdateStrategy.DEFAULT;
        var updateConfig = new UpdateConfig()
            .withParallelism((long) strategy.maxUnavailable())
            .withDelay(strategy.delaySeconds() * 1_000_000_000L)  // nanoseconds
            .withFailureAction(UpdateFailureAction.valueOf(
                strategy.failureAction().toUpperCase()));

        // Replicated mode
        var mode = new ServiceModeConfig()
            .withReplicated(new ServiceReplicatedModeOptions()
                .withReplicas(spec.replicas()));

        // Ingress overlay attachment. TaskTemplate.Networks, not ServiceSpec.Networks — the latter is
        // deprecated in the Engine API and ignored by newer daemons. Attaching only when an ingress
        // is actually configured matters: changing a task's networks RECREATES its tasks, so enabling
        // the feature cluster-wide would otherwise restart every workload at once. Adding an ingress
        // to one Service does restart that one app's tasks; that is expected and unavoidable.
        String ingressNetworkId = ingressDecision(binding);
        if (ingressNetworkId != null) {
            taskTemplate.withNetworks(List.of(new NetworkAttachmentConfig().withTarget(ingressNetworkId)));
        }

        var serviceSpec = new com.github.dockerjava.api.model.ServiceSpec()
            .withName("rigger-" + meta.namespace() + "-" + meta.name())
            .withLabels(labels)
            .withTaskTemplate(taskTemplate)
            .withMode(mode)
            .withUpdateConfig(updateConfig);

        // Published ports: the single place they are ever written. A binding that stops publishing
        // (Service deleted, or switched to ClusterIP) leaves EndpointSpec unset, which is what
        // actually removes the ports.
        if (binding != null && binding.publishesPorts()) {
            serviceSpec.withEndpointSpec(new EndpointSpec().withPorts(buildPortConfigs(binding)));
        }
        return serviceSpec;
    }

    private List<PortConfig> buildPortConfigs(ServiceBinding binding) {
        return binding.ports().stream()
            .map(p -> new PortConfig()
                .withTargetPort(p.targetPort())
                .withPublishedPort(p.port())
                .withProtocol(PortConfigProtocol.valueOf(
                    (p.protocol() == null ? "TCP" : p.protocol()).toUpperCase()))
                .withPublishMode(PortConfig.PublishMode.ingress))
            .toList();
    }
}
