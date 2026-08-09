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

        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerHost)
            .build();

        var httpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(URI.create(dockerHost))
            .maxConnections(20)
            .connectionTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
            .responseTimeout(Duration.ofSeconds(props.getReadTimeoutSeconds()))
            .build();

        this.client = DockerClientImpl.getInstance(config, httpClient);

        // Verify connectivity
        try {
            var info = client.infoCmd().exec();
            log.info("Docker connected: version={} swarm={}",
                info.getServerVersion(),
                info.getSwarm() != null ? info.getSwarm().getLocalNodeState() : "inactive");
        } catch (Exception e) {
            log.warn("Docker connectivity check failed: {}. " +
                "On Windows, ensure Docker Desktop is running and the named pipe is available.", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() throws Exception {
        if (client != null) { client.close(); log.info("Docker client closed"); }
    }

    public DockerClient get() { return client; }
}
