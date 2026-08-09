package io.rigger.gitops.agent;

import io.rigger.core.exception.ManifestValidationException;
import io.rigger.events.bus.RiggerEventBus;
import io.rigger.events.model.GitOpsSyncEvent;
import io.rigger.gitops.config.GitOpsProperties;
import io.rigger.manifest.parser.ManifestParser;
import io.rigger.store.entity.GitOpsStateEntity;
import io.rigger.store.repository.GitOpsStateRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "rigger.gitops.enabled", havingValue = "true")
public class GitOpsAgent {

    private static final Logger log = LoggerFactory.getLogger(GitOpsAgent.class);

    private final GitOpsProperties        props;
    private final GitOpsStateRepository   stateRepo;
    private final ManifestParser          parser;
    private final ManifestApplyService    applyService;
    private final RiggerEventBus          eventBus;

    private Path localRepoPath;

    public GitOpsAgent(GitOpsProperties props, GitOpsStateRepository stateRepo,
                       ManifestParser parser, ManifestApplyService applyService,
                       RiggerEventBus eventBus) {
        this.props        = props;
        this.stateRepo    = stateRepo;
        this.parser       = parser;
        this.applyService = applyService;
        this.eventBus     = eventBus;
    }

    @Scheduled(fixedDelayString = "${rigger.gitops.poll-interval-seconds:60}000")
    public void poll() {
        if (!props.isEnabled() || props.getRepository() == null) return;
        log.debug("GitOps poll: {}", props.getRepository());

        try {
            ensureCloned();
            String currentCommit  = fetchAndGetHead();
            String lastApplied    = lastAppliedCommit();

            if (currentCommit.equals(lastApplied)) {
                log.debug("GitOps: no changes (commit={})", currentCommit.substring(0, 8));
                return;
            }

            log.info("GitOps: new commit {} (was {})",
                currentCommit.substring(0, 8),
                lastApplied == null ? "none" : lastApplied.substring(0, 8));

            int applied = applyChangedManifests(currentCommit);
            persistState(currentCommit, "SUCCESS", null);
            eventBus.publish(new GitOpsSyncEvent(props.getRepository(), currentCommit, true, applied, null));
            log.info("GitOps sync complete: {} manifests applied", applied);

        } catch (Exception e) {
            log.error("GitOps sync failed: {}", e.getMessage(), e);
            persistState(null, "ERROR", e.getMessage());
            eventBus.publish(new GitOpsSyncEvent(props.getRepository(), null, false, 0, e.getMessage()));
        }
    }

    private void ensureCloned() throws Exception {
        if (localRepoPath == null) {
            localRepoPath = Files.createTempDirectory("rigger-gitops-");
            log.info("GitOps: cloning {} into {}", props.getRepository(), localRepoPath);
            Git.cloneRepository()
                .setURI(props.getRepository())
                .setDirectory(localRepoPath.toFile())
                .setBranch(props.getBranch())
                .call()
                .close();
        }
    }

    private String fetchAndGetHead() throws Exception {
        try (var git = Git.open(localRepoPath.toFile())) {
            git.fetch().call();
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                .setRef("origin/" + props.getBranch()).call();
            return git.getRepository().resolve("HEAD").getName();
        }
    }

    private int applyChangedManifests(String commit) throws Exception {
        var applied = new AtomicInteger(0);
        for (String manifestPath : props.getManifestPaths()) {
            Path dir = localRepoPath.resolve(manifestPath);
            if (!Files.isDirectory(dir)) continue;

            String namespace = props.getNamespaceMapping().getOrDefault(manifestPath, "default");
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

    private String lastAppliedCommit() {
        return stateRepo.findById(props.getRepository())
            .map(GitOpsStateEntity::getLastAppliedCommit)
            .orElse(null);
    }

    private void persistState(String commit, String result, String error) {
        var state = stateRepo.findById(props.getRepository())
            .orElse(new GitOpsStateEntity(props.getRepository(),
                commit != null ? commit : "unknown", Instant.now(), result));
        if (commit != null) state.setLastAppliedCommit(commit);
        state.setLastAppliedAt(Instant.now());
        state.setResult(result);
        state.setErrorMessage(error);
        stateRepo.save(state);
    }
}
