package io.rigger.gitops.agent;

import io.rigger.core.exception.ManifestValidationException;
import io.rigger.events.bus.RiggerEventBus;
import io.rigger.events.model.GitOpsSyncEvent;
import io.rigger.gitops.config.GitOpsConfigService;
import io.rigger.manifest.parser.ManifestParser;
import io.rigger.store.entity.GitOpsStateEntity;
import io.rigger.store.repository.GitOpsStateRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GitOps agent — polls a Git repository and applies changed manifests.
 *
 * <p>Security: the agent runs as {@code GITOPS_AGENT} role internally.
 * It can only call apply — it cannot delete resources or modify cluster config.
 *
 * <p>Flow per poll cycle:
 * <ol>
 *   <li>Fetch latest from remote (SSH key auth via JGit)</li>
 *   <li>Compare HEAD commit hash against last applied (stored in gitops_state table)</li>
 *   <li>If changed: walk manifest paths, collect changed YAML files</li>
 *   <li>Parse and validate each manifest</li>
 *   <li>Apply via ManifestApplyService (internal — bypasses HTTP)</li>
 *   <li>Update gitops_state with new commit hash</li>
 *   <li>Publish GitOpsSyncEvent</li>
 * </ol>
 */
@Component
public class GitOpsAgent {

    private static final Logger log = LoggerFactory.getLogger(GitOpsAgent.class);

    private final GitOpsConfigService     configService;
    private final GitOpsStateRepository   stateRepo;
    private final ManifestParser          parser;
    private final ManifestApplyService    applyService;
    private final RiggerEventBus          eventBus;

    private Path   localRepoPath;
    /** What localRepoPath was cloned from, so a config change can invalidate a stale clone. */
    private String clonedRepository;
    private String clonedBranch;
    private Instant lastPollAt;

    public GitOpsAgent(GitOpsConfigService configService, GitOpsStateRepository stateRepo,
                       ManifestParser parser, ManifestApplyService applyService,
                       RiggerEventBus eventBus) {
        this.configService = configService;
        this.stateRepo     = stateRepo;
        this.parser        = parser;
        this.applyService  = applyService;
        this.eventBus      = eventBus;
    }

    /**
     * Polls on a fixed schedule and resolves configuration on every cycle, so enabling GitOps or
     * changing the repository from the console takes effect on the next tick rather than needing a
     * restart. The bean is always created (it used to be conditional on the enabled property,
     * which made runtime enablement impossible); when disabled this returns immediately.
     */
    @Scheduled(fixedDelayString = "${rigger.gitops.poll-interval-seconds:60}000")
    public void poll() {
        var config = configService.current();
        if (!config.enabled() || config.repositoryUrl() == null || config.repositoryUrl().isBlank()) return;
        if (!dueForPoll(config)) return;
        log.debug("GitOps poll: {}", config.repositoryUrl());

        try {
            ensureCloned(config);
            String currentCommit  = fetchAndGetHead(config);
            String lastApplied    = lastAppliedCommit(config);

            if (currentCommit.equals(lastApplied)) {
                log.debug("GitOps: no changes (commit={})", currentCommit.substring(0, 8));
                return;
            }

            log.info("GitOps: new commit {} (was {})",
                currentCommit.substring(0, 8),
                lastApplied == null ? "none" : lastApplied.substring(0, 8));

            int applied = applyChangedManifests(config, currentCommit);
            persistState(config, currentCommit, "SUCCESS", null);
            eventBus.publish(new GitOpsSyncEvent(config.repositoryUrl(), currentCommit, true, applied, null));
            log.info("GitOps sync complete: {} manifests applied", applied);

        } catch (Exception e) {
            log.error("GitOps sync failed: {}", e.getMessage(), e);
            persistState(config, null, "ERROR", e.getMessage());
            eventBus.publish(new GitOpsSyncEvent(config.repositoryUrl(), null, false, 0, e.getMessage()));
        }
    }

    /**
     * Honours the configured poll interval on top of the fixed {@code @Scheduled} tick.
     *
     * <p>Spring resolves {@code fixedDelayString} once at startup, so a stored interval can't
     * change the schedule itself. Gating here makes the stored value meaningful for intervals
     * <em>longer</em> than the tick; it cannot poll faster than the tick, which is therefore the
     * effective floor (defaults to 60s, set {@code rigger.gitops.poll-interval-seconds} lower if a
     * faster floor is needed).
     */
    private boolean dueForPoll(GitOpsConfigService.GitOpsSettings config) {
        int configured = config.pollIntervalSeconds();
        if (configured <= 0 || lastPollAt == null) {
            lastPollAt = Instant.now();
            return true;
        }
        if (Instant.now().isBefore(lastPollAt.plusSeconds(configured))) return false;
        lastPollAt = Instant.now();
        return true;
    }

    private void ensureCloned(GitOpsConfigService.GitOpsSettings config) throws Exception {
        // Repointing the agent at a different repo/branch must discard the old working copy,
        // otherwise it would keep syncing whatever was cloned first.
        boolean stale = localRepoPath != null
            && (!config.repositoryUrl().equals(clonedRepository) || !config.branch().equals(clonedBranch));
        if (stale) {
            log.info("GitOps: configuration changed ({} @ {}), discarding previous clone",
                config.repositoryUrl(), config.branch());
            localRepoPath = null;
        }

        if (localRepoPath == null) {
            localRepoPath = Files.createTempDirectory("rigger-gitops-");
            log.info("GitOps: cloning {} into {}", config.repositoryUrl(), localRepoPath);
            Git.cloneRepository()
                .setURI(config.repositoryUrl())
                .setDirectory(localRepoPath.toFile())
                .setBranch(config.branch())
                .call()
                .close();
            clonedRepository = config.repositoryUrl();
            clonedBranch     = config.branch();
        }
    }

    private String fetchAndGetHead(GitOpsConfigService.GitOpsSettings config) throws Exception {
        try (var git = Git.open(localRepoPath.toFile())) {
            git.fetch().call();
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                .setRef("origin/" + config.branch()).call();
            return git.getRepository().resolve("HEAD").getName();
        }
    }

    private int applyChangedManifests(GitOpsConfigService.GitOpsSettings config, String commit) throws Exception {
        var applied = new AtomicInteger(0);
        for (String manifestPath : config.manifestPaths()) {
            Path dir = localRepoPath.resolve(manifestPath);
            if (!Files.isDirectory(dir)) continue;

            String namespace = config.namespaceMapping().getOrDefault(manifestPath, "default");
            var manifests = parser.parseDirectory(dir);
            for (var pm : manifests) {
                try {
                    applyService.apply(pm, namespace, "gitops-agent[" + commit.substring(0, 8) + "]");
                    applied.incrementAndGet();
                } catch (ManifestValidationException e) {
                    log.warn("GitOps: skipping invalid manifest {}: {}", pm.source(), e.getMessage());
                }
            }
        }
        return applied.get();
    }

    private String lastAppliedCommit(GitOpsConfigService.GitOpsSettings config) {
        return stateRepo.findById(config.repositoryUrl())
            .map(GitOpsStateEntity::getLastAppliedCommit)
            .orElse(null);
    }

    private void persistState(GitOpsConfigService.GitOpsSettings config, String commit, String result, String error) {
        var state = stateRepo.findById(config.repositoryUrl())
            .orElse(new GitOpsStateEntity(config.repositoryUrl(),
                commit != null ? commit : "unknown", Instant.now(), result));
        if (commit != null) state.setLastAppliedCommit(commit);
        state.setLastAppliedAt(Instant.now());
        state.setResult(result);
        state.setErrorMessage(error);
        stateRepo.save(state);
    }
}
