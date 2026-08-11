package io.rigger.swarm.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.rigger.swarm.config.SwarmAdapterProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;

/**
 * Lifecycle-managed docker-java client factory.
 *
 * <p>Supports Linux (Unix socket), Windows (named pipe via npipe://),
 * and remote TCP connections. Transport is selected automatically
 * based on {@link SwarmAdapterProperties#effectiveDockerHost()}.
 */
@Component
public class DockerClientFactory {

    private static final Logger log = LoggerFactory.getLogger(DockerClientFactory.class);

    private final SwarmAdapterProperties props;
    private DockerClient client;

    public DockerClientFactory(SwarmAdapterProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        String dockerHost = props.effectiveDockerHost();
        log.info("Connecting to Docker daemon: {}", dockerHost);

        var configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerHost);

        // TLS only makes sense for a remote tcp:// daemon; a local socket/named pipe is already
        // authenticated by filesystem permissions and docker-java rejects the combination.
        boolean tls = props.getTlsCertPath() != null && !props.getTlsCertPath().isBlank();
        if (tls) {
            configBuilder.withDockerTlsVerify(true).withDockerCertPath(props.getTlsCertPath());
        }
        var config = configBuilder.build();

        var httpBuilder = new ApacheDockerHttpClient.Builder()
            .dockerHost(URI.create(dockerHost))
            .maxConnections(20)
            .connectionTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
            .responseTimeout(Duration.ofSeconds(props.getReadTimeoutSeconds()));
        if (tls) {
            httpBuilder.sslConfig(config.getSSLConfig()); // ignored unless actually configured
            log.info("Docker TLS enabled, certs from {}", props.getTlsCertPath());
        }
        var httpClient = httpBuilder.build();

        this.client = DockerClientImpl.getInstance(config, httpClient);

        // Verify connectivity
        try {
            var info = client.infoCmd().exec();
            log.info("Docker connected: version={} swarm={}",
                info.getServerVersion(),
                info.getSwarm() != null ? info.getSwarm().getLocalNodeState() : "inactive");
        } catch (Exception | LinkageError e) {
            // LinkageError, not just Exception: the npipe transport loads kernel32 through JNA, so a
            // named-pipe host on a non-Windows JVM throws UnsatisfiedLinkError — an Error, which
            // would escape this probe and kill startup outright instead of warning as intended.
            // Deliberately not fatal — the server still serves the API/UI and reconnects on the
            // next adapter call. But every workload operation will fail until Docker answers, so
            // the message has to say exactly what to check rather than just reporting the error.
            log.warn("Docker is NOT reachable at {} — the API will start but every workload "
                + "operation will fail until this is fixed. Cause: {}", dockerHost, e.getMessage());
            log.warn("Check: {}", remediationHint(dockerHost));
        }
    }

    /** Remediation text per transport — the causes are completely different in each case. */
    static String remediationHint(String dockerHost) {
        if (dockerHost.startsWith("npipe:")) {
            return "is Docker Desktop running (whale icon, 'Engine running')? Docker Desktop also "
                + "exposes " + dockerHost + " only when 'Expose daemon' / the default engine pipe "
                + "is enabled; otherwise set DOCKER_SOCKET to the pipe reported by "
                + "`docker context inspect` (often npipe:////./pipe/dockerDesktopLinuxEngine).";
        }
        if (dockerHost.startsWith("unix:")) {
            return "does the socket exist and is this user in the 'docker' group? "
                + "Override with DOCKER_SOCKET if the daemon uses a non-default path.";
        }
        return "is the remote daemon listening and reachable, and does it need TLS? "
            + "Set rigger.docker.tlsCertPath when the daemon requires client certificates.";
    }

    @PreDestroy
    public void destroy() throws Exception {
        if (client != null) { client.close(); log.info("Docker client closed"); }
    }

    public DockerClient get() { return client; }
}
