package io.rigger.store.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Tracks the last successfully applied Git commit per repository.
 * The GitOps agent uses this to detect new commits and avoid re-applying.
 */
@Entity
@Table(name = "gitops_state")
public class GitOpsStateEntity {

    @Id
    @Column(nullable = false)
    private String repositoryUrl;

    @Column(nullable = false)
    private String lastAppliedCommit;

    @Column(nullable = false, columnDefinition = "TEXT")
    private Instant lastAppliedAt;

    @Column(nullable = false)
    private String result;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    protected GitOpsStateEntity() {}

    public GitOpsStateEntity(String repositoryUrl, String lastAppliedCommit,
                              Instant lastAppliedAt, String result) {
        this.repositoryUrl = repositoryUrl;
        this.lastAppliedCommit = lastAppliedCommit;
        this.lastAppliedAt = lastAppliedAt;
        this.result = result;
    }

    public String getRepositoryUrl() { return repositoryUrl; }
    public String getLastAppliedCommit() { return lastAppliedCommit; }
    public void setLastAppliedCommit(String c) { this.lastAppliedCommit = c; }
    public Instant getLastAppliedAt() { return lastAppliedAt; }
    public void setLastAppliedAt(Instant t) { this.lastAppliedAt = t; }
    public String getResult() { return result; }
    public void setResult(String r) { this.result = r; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String e) { this.errorMessage = e; }
}