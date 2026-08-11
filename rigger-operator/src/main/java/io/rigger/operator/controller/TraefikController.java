package io.rigger.operator.controller;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.*;
import io.rigger.swarm.adapter.NetworkAdapter;
import io.rigger.swarm.client.DockerClientFactory;
import io.rigger.swarm.config.IngressProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Provisions the cluster's Traefik ingress controller: the shared overlay network and the Traefik
 * Swarm service itself. Rigger owns this rather than expecting the operator to deploy Traefik by
 * hand, so {@code spec.ingress} on a Service works out of the box.
 *
 * <p>Runs inside the reconciliation loop and is idempotent by construction — it converges the Swarm
 * service to the desired spec and does nothing when they already agree, so it survives restarts
 * without special-casing them.
 *
 * <h2>Three things here are not free choices</h2>
 *
 * <ul>
 *   <li><strong>Traefik is not built as a Rigger Deployment</strong>, even though that would have
 *       been less code. It needs the Docker socket bind-mounted and a manager placement constraint,
 *       and {@code DeploymentSpec} has no volumes field <em>on purpose</em>: adding one would let any
 *       namespace-scoped DEPLOYER bind-mount {@code /var/run/docker.sock} and own the whole cluster.
 *       So the Swarm service is built directly here, where no user input reaches it.</li>
 *   <li><strong>It must not carry {@code rigger.io/managed=true}.</strong>
 *       {@code DeploymentController} deletes every managed Swarm service with no matching row in the
 *       {@code resources} table, so a managed Traefik would be garbage-collected within 15 seconds of
 *       every creation, forever. It carries {@code rigger.io/component=ingress-controller}, which
 *       {@code ServiceAdapter.listManaged()} never matches.</li>
 *   <li><strong>ACME storage is a named volume.</strong> Without persistence every restart
 *       re-requests certificates and Let's Encrypt eventually rate-limits the domain — a failure that
 *       shows up weeks later with no obvious cause.</li>
 * </ul>
 *
 * <p>Traefik v3 provider spellings only: {@code providers.swarm}, never
 * {@code providers.docker.swarmMode}. The v2 form does not error on v3 — the provider simply never
 * discovers anything and every request 404s.
 */
@Component
public class TraefikController {

    private static final Logger log = LoggerFactory.getLogger(TraefikController.class);

    static final String SERVICE_NAME    = "rigger-ingress-controller";
    static final String LABEL_COMPONENT = "rigger.io/component";
    static final String COMPONENT_VALUE = "ingress-controller";
    static final String LABEL_SPEC_HASH = "rigger.io/spec-hash";

    private final DockerClientFactory factory;
    private final NetworkAdapter      networks;
    private final IngressProperties   props;

    public TraefikController(DockerClientFactory factory, NetworkAdapter networks, IngressProperties props) {
        this.factory = factory;
        this.networks = networks;
        this.props = props;
    }

    private DockerClient docker() { return factory.get(); }

    /** @return number of changes made (0 when already converged). */
    public int reconcile() {
        if (!props.isEnabled()) return removeIfPresent();

        String networkId = networks.ensureOverlay(props.getNetwork());
        var desired  = buildSpec(networkId);
        var existing = findController();

        if (existing.isEmpty()) {
            log.info("Deploying Traefik ingress controller ({}) on network {}", props.getImage(), props.getNetwork());
            docker().createServiceCmd(desired).exec();
            return 1;
        }

        var current = existing.get();
        String storedHash = current.getSpec() != null && current.getSpec().getLabels() != null
                ? current.getSpec().getLabels().get(LABEL_SPEC_HASH) : null;
        String wantedHash = desired.getLabels().get(LABEL_SPEC_HASH);
        if (Objects.equals(storedHash, wantedHash)) return 0;

        log.info("Updating Traefik ingress controller (spec-hash {} -> {})", storedHash, wantedHash);
        long version = current.getVersion() != null ? current.getVersion().getIndex() : 0L;
        docker().updateServiceCmd(current.getId(), desired).withVersion(version).exec();
        return 1;
    }

    private int removeIfPresent() {
        var existing = findController();
        if (existing.isEmpty()) return 0;
        log.info("rigger.ingress.enabled=false — removing the Traefik ingress controller");
        docker().removeServiceCmd(existing.get().getId()).exec();
        networks.invalidate(props.getNetwork());
        return 1;
    }

    /** Found by label, not by name: {@code withNameFilter} is a substring match on the Engine side. */
    private Optional<Service> findController() {
        return docker().listServicesCmd()
                .withLabelFilter(Map.of(LABEL_COMPONENT, COMPONENT_VALUE))
                .exec()
                .stream()
                .findFirst();
    }

    // ── spec construction ────────────────────────────────────────────────

    private ServiceSpec buildSpec(String networkId) {
        var args = traefikArgs();

        var mounts = new ArrayList<Mount>();
        mounts.add(new Mount()
                .withType(MountType.BIND)
                .withSource(props.getNodeDockerSocket())
                .withTarget("/var/run/docker.sock")
                .withReadOnly(true));           // read-only: Traefik only ever needs to observe
        mounts.add(new Mount()
                .withType(MountType.VOLUME)
                .withSource(props.getAcmeVolume())
                .withTarget("/acme"));

        String apiVersion = dockerApiVersion();
        var containerSpec = new ContainerSpec()
                .withImage(props.getImage())
                .withArgs(args)
                // Traefik 3.3's Swarm provider does not negotiate the Docker API version: it defaults
                // to 1.24, which modern daemons reject ("client version 1.24 is too old"). Traefik
                // still starts and still reports 1/1 — it just discovers nothing and 404s every
                // request. This env var is what makes the provider work at all.
                .withEnv(List.of("DOCKER_API_VERSION=" + apiVersion))
                .withMounts(mounts);

        var taskTemplate = new TaskSpec()
                .withContainerSpec(containerSpec)
                // Only a manager node can serve the Docker API the swarm provider needs.
                .withPlacement(new ServicePlacement().withConstraints(List.of("node.role == manager")))
                // TaskTemplate.Networks, not the deprecated ServiceSpec.Networks.
                .withNetworks(List.of(new NetworkAttachmentConfig().withTarget(networkId)));

        var ports = new ArrayList<PortConfig>();
        ports.add(publish(props.getHttpPort(), 80));
        ports.add(publish(props.getHttpsPort(), 443));

        var labels = new LinkedHashMap<String, String>();
        labels.put(LABEL_COMPONENT, COMPONENT_VALUE);
        labels.put("rigger.io/kind", "IngressController");
        // Traefik must not route to itself unless the dashboard is explicitly asked for.
        labels.put("traefik.enable", Boolean.toString(props.isDashboard()));

        var spec = new ServiceSpec()
                .withName(SERVICE_NAME)
                .withTaskTemplate(taskTemplate)
                .withMode(new ServiceModeConfig().withReplicated(
                        new ServiceReplicatedModeOptions().withReplicas(1)))
                .withEndpointSpec(new EndpointSpec().withPorts(ports));

        // The hash covers everything above, so a changed image/entrypoint/resolver converges on the
        // next cycle and an unchanged config never re-updates (which would restart the ingress).
        labels.put(LABEL_SPEC_HASH, Integer.toHexString(
                (args + "|" + props.getImage() + "|" + props.getNodeDockerSocket() + "|"
                 + props.getAcmeVolume() + "|" + networkId + "|" + props.getHttpPort() + ":"
                 + props.getHttpsPort() + "|" + props.isDashboard() + "|" + apiVersion).hashCode()));
        return spec.withLabels(labels);
    }

    /**
     * Configured API version, or the one this daemon actually reports. Using the daemon's own value
     * is always accepted (it is never below its own minimum), and it is stable for a given host — so
     * folding it into the spec-hash does not cause churn.
     */
    private String dockerApiVersion() {
        if (!props.getDockerApiVersion().isBlank()) return props.getDockerApiVersion();
        try {
            String reported = docker().versionCmd().exec().getApiVersion();
            if (reported != null && !reported.isBlank()) return reported;
        } catch (Exception e) {
            log.warn("Could not read the Docker API version for Traefik ({}); falling back to 1.44",
                    e.getMessage());
        }
        return "1.44";
    }

    private static PortConfig publish(int published, int target) {
        return new PortConfig()
                .withTargetPort(target)
                .withPublishedPort(published)
                .withProtocol(PortConfigProtocol.TCP)
                .withPublishMode(PortConfig.PublishMode.ingress);
    }

    private List<String> traefikArgs() {
        var args = new ArrayList<String>();
        args.add("--providers.swarm=true");
        args.add("--providers.swarm.endpoint=unix:///var/run/docker.sock");
        // Nothing is exposed unless it carries traefik.enable=true — see TraefikLabels.
        args.add("--providers.swarm.exposedByDefault=false");
        args.add("--providers.swarm.network=" + props.getNetwork());
        args.add("--providers.swarm.refreshSeconds=15");
        args.add("--entryPoints." + props.getEntryPoint() + ".address=:80");
        args.add("--entryPoints." + props.getTlsEntryPoint() + ".address=:443");
        args.add("--log.level=INFO");
        if (props.isDashboard()) {
            args.add("--api.dashboard=true");
            args.add("--api.insecure=true");
        }
        if (!props.getCertResolver().isBlank()) {
            String r = props.getCertResolver();
            args.add("--certificatesResolvers." + r + ".acme.email=" + props.getAcmeEmail());
            args.add("--certificatesResolvers." + r + ".acme.storage=/acme/acme.json");
            args.add("--certificatesResolvers." + r + ".acme.tlsChallenge=true");
        }
        return args;
    }
}
