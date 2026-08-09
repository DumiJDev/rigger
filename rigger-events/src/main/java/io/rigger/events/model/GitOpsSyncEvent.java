package io.rigger.events.model;

/** Fired when the GitOps agent completes a sync (success or failure). */
public final class GitOpsSyncEvent extends RiggerEvent {
    private final String repositoryUrl;
    private final String commitHash;
    private final boolean success;
    private final int manifestsApplied;
    private final String errorMessage;

    public GitOpsSyncEvent(String repoUrl, String hash, boolean success, int applied, String error) {
        super();
        this.repositoryUrl = repoUrl; this.commitHash = hash;
        this.success = success; this.manifestsApplied = applied; this.errorMessage = error;
    }

    @Override public String type() { return "gitops.sync"; }
    public String repositoryUrl() { return repositoryUrl; }
    public String commitHash() { return commitHash; }
    public boolean isSuccess() { return success; }
    public int manifestsApplied() { return manifestsApplied; }
    public String errorMessage() { return errorMessage; }
}