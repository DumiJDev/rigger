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
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "rigger.store")
public class StoreProperties {

    /** Store backend type: sqlite (default) or postgresql. */
    private String type = "sqlite";

    /** Path to the SQLite database file. Only used when type=sqlite. */
    private String path = "/var/lib/rigger/state.db";

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}