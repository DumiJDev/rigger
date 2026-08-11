package io.rigger.swarm.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for Rigger's built-in Traefik ingress controller.
 *
 * <pre>
 * rigger:
 *   ingress:
 *     enabled: true
 *     network: rigger-ingress
 *     image: traefik:v3.3
 *     http-port: 80
 *     https-port: 443
 *     node-docker-socket: /var/run/docker.sock
 * </pre>
 *
 * <p><strong>Traefik v3 label and provider spellings only.</strong> The v2 spellings
 * ({@code traefik.docker.network}, {@code traefik.docker.lbswarm},
 * {@code providers.docker.swarmMode}) do not error on v3 — they are ignored, and the only
 * observable symptom is a 404 from a real HTTP request against the host. See {@code TraefikLabels}.
 */
@Component
@ConfigurationProperties(prefix = "rigger.ingress")
public class IngressProperties {

    private static final Logger log = LoggerFactory.getLogger(IngressProperties.class);

    /** Off by default: enabling it creates an overlay network and deploys a Traefik service. */
    private boolean enabled = false;

    /** Overlay network shared by Traefik and every ingress-exposed workload. */
    private String network = "rigger-ingress";

    private String image = "traefik:v3.3";

    /** Traefik entrypoint names. Referenced by the router labels, so they must match the args. */
    private String entryPoint    = "web";
    private String tlsEntryPoint = "websecure";

    private int httpPort  = 80;
    private int httpsPort = 443;

    /**
     * ACME cert resolver name. Blank (the default) means no ACME at all — {@code tls: true} then
     * uses Traefik's own self-signed certificate, which is the only thing that can work on a
     * single-node CI runner or any host without a public DNS name.
     */
    private String certResolver = "";

    /** Contact address for ACME registration. Required by Let's Encrypt when certResolver is set. */
    private String acmeEmail = "";

    /**
     * Named Docker volume holding {@code acme.json}. Without persistent storage every restart
     * re-requests certificates and Let's Encrypt rate-limits the domain — a failure that only
     * surfaces weeks later, when the limit is finally hit.
     */
    private String acmeVolume = "rigger-traefik-acme";

    /** Exposes Traefik's own dashboard on the API entrypoint. Dev convenience; off by default. */
    private boolean dashboard = false;

    /**
     * Path Traefik binds to reach the Docker API <em>from inside its own container on the node</em>.
     * <strong>Deliberately separate from {@code rigger.docker.socket}</strong>, which is how the
     * Rigger server process reaches Docker and may legitimately be a Windows named pipe or a remote
     * {@code tcp://} host — neither of which can be bind-mounted into a Linux Traefik container.
     */
    private String nodeDockerSocket = "/var/run/docker.sock";

    /**
     * Value of {@code DOCKER_API_VERSION} in Traefik's container. Blank (the default) means
     * auto-detect from the daemon Rigger itself is talking to.
     *
     * <p><strong>Not optional in practice.</strong> Traefik 3.3's Swarm provider builds its Docker
     * client without API-version negotiation and defaults to <em>1.24</em>, which every modern daemon
     * rejects outright ({@code client version 1.24 is too old. Minimum supported API version is
     * 1.40}). Traefik still starts, still reports 1/1 replicas and still answers HTTP — it simply
     * discovers no services, so every request 404s. Exactly the failure mode that only a real HTTP
     * request can detect.
     */
    private String dockerApiVersion = "";

    @PostConstruct
    void warnOnUnmountableSocket() {
        if (!enabled) return;
        String s = nodeDockerSocket == null ? "" : nodeDockerSocket.trim();
        if (s.regionMatches(true, 0, "npipe:", 0, 6) || s.replace('\\', '/').startsWith("//./pipe/")) {
            log.warn("rigger.ingress.node-docker-socket looks like a Windows named pipe ({}). Traefik "
                   + "runs in a Linux container and can only bind-mount a Unix socket path — the "
                   + "ingress controller will fail to start. Set it to /var/run/docker.sock.", s);
        }
        if (!certResolver.isBlank() && acmeEmail.isBlank()) {
            log.warn("rigger.ingress.cert-resolver='{}' is set but acme-email is empty — Let's Encrypt "
                   + "registration will be rejected.", certResolver);
        }
    }

    /**
     * Stable identity of every property that changes what gets written onto a workload's Swarm
     * service. Folded into the Deployment spec-hash so that toggling {@code enabled} off, or
     * renaming an entrypoint, actually removes/rewrites the labels instead of leaving stale ones
     * behind forever.
     */
    public String signature() {
        return enabled + "|" + network + "|" + entryPoint + "|" + tlsEntryPoint + "|" + certResolver;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getEntryPoint() { return entryPoint; }
    public void setEntryPoint(String entryPoint) { this.entryPoint = entryPoint; }
    public String getTlsEntryPoint() { return tlsEntryPoint; }
    public void setTlsEntryPoint(String tlsEntryPoint) { this.tlsEntryPoint = tlsEntryPoint; }
    public int getHttpPort() { return httpPort; }
    public void setHttpPort(int httpPort) { this.httpPort = httpPort; }
    public int getHttpsPort() { return httpsPort; }
    public void setHttpsPort(int httpsPort) { this.httpsPort = httpsPort; }
    public String getCertResolver() { return certResolver; }
    public void setCertResolver(String certResolver) { this.certResolver = certResolver == null ? "" : certResolver.trim(); }
    public String getAcmeEmail() { return acmeEmail; }
    public void setAcmeEmail(String acmeEmail) { this.acmeEmail = acmeEmail == null ? "" : acmeEmail.trim(); }
    public String getAcmeVolume() { return acmeVolume; }
    public void setAcmeVolume(String acmeVolume) { this.acmeVolume = acmeVolume; }
    public boolean isDashboard() { return dashboard; }
    public void setDashboard(boolean dashboard) { this.dashboard = dashboard; }
    public String getNodeDockerSocket() { return nodeDockerSocket; }
    public void setNodeDockerSocket(String nodeDockerSocket) { this.nodeDockerSocket = nodeDockerSocket; }
    public String getDockerApiVersion() { return dockerApiVersion; }
    public void setDockerApiVersion(String v) { this.dockerApiVersion = v == null ? "" : v.trim(); }
}
