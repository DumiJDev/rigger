package io.rigger.store.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persisted GitOps agent configuration — a single row (id {@code "default"}), mirroring the
 * single-repository shape of {@link GitOpsStateEntity}.
 *
 * <p>Exists so the agent can be reconfigured from the console at runtime; before this, config came
 * only from environment variables and needed a restart. Holds no credentials, only a path to an
 * SSH key already present on the server.
 */
@Entity
@Table(name = "gitops_config")
public class GitOpsConfigEntity {

    public static final String SINGLETON_ID = "default";

    @Id
    @Column(nullable = false)
    private String id = SINGLETON_ID;

    // INTEGER to match the SQLite column — Hibernate's strict validation rejects a plain boolean here.
    @Column(nullable = false, columnDefinition = "INTEGER")
    private boolean enabled;

    @Column(name = "repository_url")
    private String repositoryUrl;

    @Column(nullable = false)
    private String branch = "main";

    @Column(name = "ssh_key_path")
    private String sshKeyPath;

    @Column(name = "poll_interval_seconds", nullable = false)
    private int pollIntervalSeconds = 60;

    /** Comma-separated repository paths to scan for manifests. */
    @Column(name = "manifest_paths", nullable = false, columnDefinition = "TEXT")
    private String manifestPaths = "manifests/";

    /** JSON object mapping manifest path to target namespace. */
    @Column(name = "namespace_mapping", nullable = false, columnDefinition = "TEXT")
    private String namespaceMapping = "{}";

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private String updatedBy;

    public String getId()                  { return id; }
    public void setId(String id)           { this.id = id; }
    public boolean isEnabled()             { return enabled; }
    public void setEnabled(boolean e)      { this.enabled = e; }
    public String getRepositoryUrl()       { return repositoryUrl; }
    public void setRepositoryUrl(String r) { this.repositoryUrl = r; }
    public String getBranch()              { return branch; }
    public void setBranch(String b)        { this.branch = b; }
    public String getSshKeyPath()          { return sshKeyPath; }
    public void setSshKeyPath(String k)    { this.sshKeyPath = k; }
    public int getPollIntervalSeconds()    { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(int s) { this.pollIntervalSeconds = s; }
    public String getManifestPaths()       { return manifestPaths; }
    public void setManifestPaths(String p) { this.manifestPaths = p; }
    public String getNamespaceMapping()    { return namespaceMapping; }
    public void setNamespaceMapping(String m) { this.namespaceMapping = m; }
    public Instant getUpdatedAt()          { return updatedAt; }
    public void setUpdatedAt(Instant t)    { this.updatedAt = t; }
    public String getUpdatedBy()           { return updatedBy; }
    public void setUpdatedBy(String u)     { this.updatedBy = u; }
}
