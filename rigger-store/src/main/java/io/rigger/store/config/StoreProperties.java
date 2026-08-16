package io.rigger.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Rigger store.
 *
 * <pre>
 * rigger:
 *   store:
 *     type: sqlite          # sqlite | postgresql
 *     path: /var/lib/rigger/state.db
 *     host: localhost       # only used when type=postgresql
 *     port: 5432
 *     database: rigger
 *     username: rigger
 *     password: ""
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "rigger.store")
public class StoreProperties {

    /** Store backend type: sqlite (default) or postgresql. */
    private String type = "sqlite";

    /** Path to the SQLite database file. Only used when type=sqlite. */
    private String path = "/var/lib/rigger/state.db";

    /** Postgres host. Only used when type=postgresql. */
    private String host = "localhost";

    /** Postgres port. Only used when type=postgresql. */
    private int port = 5432;

    /** Postgres database name. Only used when type=postgresql. */
    private String database = "rigger";

    /** Postgres username. Only used when type=postgresql. */
    private String username = "rigger";

    /** Postgres password. Only used when type=postgresql. */
    private String password = "";

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}