package io.rigger.swarm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Docker Swarm adapter.
 *
 * <pre>
 * rigger:
 *   docker:
 *     # Linux default (Unix socket):
 *     socket: /var/run/docker.sock
 *
 *     # Windows Docker Desktop (named pipe):
 *     socket: npipe:////./pipe/docker_engine
 *
 *     # Remote Docker daemon:
 *     host: tcp://10.0.0.10:2375
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "rigger.docker")
public class SwarmAdapterProperties {

    /** Unix socket path or Windows named pipe. Ignored when host is set. */
    private String socket = "/var/run/docker.sock";

    /** Remote Docker host (tcp:// or unix://). Overrides socket when set. */
    private String host;

    private String tlsCertPath;
    private int    connectTimeoutSeconds = 10;
    private int    readTimeoutSeconds    = 60;

    public String getSocket() { return socket; }
    public void   setSocket(String socket) { this.socket = socket; }
    public String getHost()   { return host; }
    public void   setHost(String host) { this.host = host; }
    public String getTlsCertPath() { return tlsCertPath; }
    public void   setTlsCertPath(String tlsCertPath) { this.tlsCertPath = tlsCertPath; }
    public int    getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void   setConnectTimeoutSeconds(int t) { this.connectTimeoutSeconds = t; }
    public int    getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void   setReadTimeoutSeconds(int t) { this.readTimeoutSeconds = t; }

    /**
     * Returns true when connecting via Unix socket or named pipe (local Docker).
     * Returns false when a TCP host is explicitly configured.
     */
    public boolean isLocalSocket() { return host == null || host.isBlank(); }

    /**
     * Returns the effective Docker host URI used by docker-java.
     * Priority: explicit host > socket value.
     */
    public String effectiveDockerHost() {
        if (!isLocalSocket()) return host;
        // Normalise socket path to a URI
        if (socket.startsWith("npipe://") || socket.startsWith("unix://") || socket.startsWith("tcp://")) {
            return socket;
        }
        if (socket.startsWith("npipe:")) return socket; // already a named pipe URI
        return "unix://" + socket;
    }
}
