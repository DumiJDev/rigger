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

    /**
     * Directory holding ca.pem/cert.pem/key.pem for a TLS-protected remote daemon.
     * Only meaningful together with a tcp:// host; ignored for local socket/named pipe.
     */
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
        if (!isLocalSocket()) return host.trim();
        String s = socket.trim();
        if (s.regionMatches(true, 0, "npipe:", 0, 6)) return namedPipe(s.substring(6));
        if (s.startsWith("unix://") || s.startsWith("tcp://")) return s;
        // Bare named pipe, as Windows itself displays it (\\.\pipe\docker_engine).
        if (s.replace('\\', '/').startsWith("//./pipe/")) return namedPipe(s);
        return "unix://" + s;
    }

    /**
     * Canonicalises any named-pipe spelling to docker-java's own form,
     * {@code npipe:////./pipe/docker_engine}. Windows shows pipes with backslashes and a varying
     * number of leading separators, and {@code URI.create} in the client factory rejects
     * backslashes outright — so normalise here rather than fail at startup.
     */
    private static String namedPipe(String rest) {
        String path = rest.replace('\\', '/');
        int i = 0;
        while (i < path.length() && path.charAt(i) == '/') i++;
        return "npipe:////" + path.substring(i);
    }
}
