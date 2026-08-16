package io.rigger.gitops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.security.crypto.SecretEncryptor;
import io.rigger.store.entity.GitOpsConfigEntity;
import io.rigger.store.repository.GitOpsConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the effective GitOps configuration: the database row if one exists, otherwise the
 * {@code rigger.gitops.*} properties.
 *
 * <p>The properties remain the bootstrap path (so an environment-variable-only deployment keeps
 * working exactly as before), but once an operator saves config through the console the stored row
 * wins. The agent resolves through here on every poll rather than caching at startup, so changes
 * take effect on the next cycle instead of needing a restart.
 */
@Service
public class GitOpsConfigService {

    private static final Logger log = LoggerFactory.getLogger(GitOpsConfigService.class);

    private final GitOpsConfigRepository repo;
    private final GitOpsProperties       properties;
    private final SecretEncryptor        encryptor;
    private final ObjectMapper           mapper = new ObjectMapper();

    public GitOpsConfigService(GitOpsConfigRepository repo, GitOpsProperties properties,
                                SecretEncryptor encryptor) {
        this.repo = repo;
        this.properties = properties;
        this.encryptor = encryptor;
    }

    /** Effective config, database-first with property fallback. */
    public GitOpsSettings current() {
        return repo.findById(GitOpsConfigEntity.SINGLETON_ID)
            .map(this::fromEntity)
            .orElseGet(this::fromProperties);
    }

    /** Whether the stored row (rather than the properties) is what's in effect. */
    public boolean isStored() {
        return repo.existsById(GitOpsConfigEntity.SINGLETON_ID);
    }

    public GitOpsSettings save(GitOpsSettings settings, String updatedBy) {
        var entity = repo.findById(GitOpsConfigEntity.SINGLETON_ID).orElseGet(GitOpsConfigEntity::new);
        entity.setId(GitOpsConfigEntity.SINGLETON_ID);
        entity.setEnabled(settings.enabled());
        entity.setRepositoryUrl(settings.repositoryUrl());
        entity.setBranch(settings.branch() == null || settings.branch().isBlank() ? "main" : settings.branch());
        entity.setSshKeyPath(settings.sshKeyPath());
        entity.setAuthType(settings.authType() == null || settings.authType().isBlank() ? "ssh" : settings.authType());
        entity.setHttpsUsername(settings.httpsUsername());
        // A blank token means "keep the currently stored one" — the console never re-sends the
        // decrypted value, so an unchanged form field must not wipe it.
        if (settings.httpsToken() != null && !settings.httpsToken().isBlank()) {
            entity.setHttpsTokenEncrypted(encryptor.encrypt(settings.httpsToken()));
        }
        entity.setPollIntervalSeconds(settings.pollIntervalSeconds() > 0 ? settings.pollIntervalSeconds() : 60);
        entity.setManifestPaths(String.join(",", settings.manifestPaths()));
        try {
            entity.setNamespaceMapping(mapper.writeValueAsString(settings.namespaceMapping()));
        } catch (Exception e) {
            throw new IllegalArgumentException("namespaceMapping is not serialisable", e);
        }
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(updatedBy);
        repo.save(entity);
        log.info("GitOps configuration updated by {} (enabled={}, repository={})",
            updatedBy, settings.enabled(), settings.repositoryUrl());
        return current();
    }

    private GitOpsSettings fromEntity(GitOpsConfigEntity e) {
        Map<String, String> mapping;
        try {
            mapping = mapper.readValue(e.getNamespaceMapping(), Map.class);
        } catch (Exception ex) {
            log.warn("Stored GitOps namespaceMapping is unreadable, treating as empty: {}", ex.getMessage());
            mapping = new LinkedHashMap<>();
        }
        String httpsToken = e.getHttpsTokenEncrypted() == null ? null : encryptor.decrypt(e.getHttpsTokenEncrypted());
        return new GitOpsSettings(
            e.isEnabled(), e.getRepositoryUrl(), e.getBranch(), e.getSshKeyPath(),
            e.getAuthType(), e.getHttpsUsername(), httpsToken,
            e.getPollIntervalSeconds(),
            Arrays.stream(e.getManifestPaths().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
            mapping, e.getUpdatedAt(), e.getUpdatedBy());
    }

    private GitOpsSettings fromProperties() {
        return new GitOpsSettings(
            properties.isEnabled(), properties.getRepository(), properties.getBranch(),
            properties.getSshKeyPath(), properties.getAuthType(),
            properties.getHttpsUsername(), properties.getHttpsToken(),
            properties.getPollIntervalSeconds(),
            properties.getManifestPaths(), properties.getNamespaceMapping(), null, null);
    }

    /** Effective GitOps configuration, independent of whether it came from the DB or properties. */
    public record GitOpsSettings(
            boolean enabled,
            String repositoryUrl,
            String branch,
            String sshKeyPath,
            String authType,
            String httpsUsername,
            String httpsToken,
            int pollIntervalSeconds,
            List<String> manifestPaths,
            Map<String, String> namespaceMapping,
            Instant updatedAt,
            String updatedBy
    ) {
        public GitOpsSettings {
            if (manifestPaths == null) manifestPaths = List.of("manifests/");
            if (namespaceMapping == null) namespaceMapping = Map.of();
            if (authType == null || authType.isBlank()) authType = "ssh";
        }

        public boolean isHttps() {
            return "https".equalsIgnoreCase(authType);
        }
    }
}
