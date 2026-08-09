package io.rigger.gitops.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.core.util.UlidGenerator;
import io.rigger.manifest.parser.ParsedManifest;
import io.rigger.store.entity.ResourceEntity;
import io.rigger.store.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

/**
 * Internal apply service used by the GitOps agent.
 * Bypasses HTTP — writes directly to the store.
 * The GitOps agent role is enforced by the agent itself (not RBAC aspect here).
 */
@Service
public class ManifestApplyService {

    private final ResourceRepository store;
    private final ObjectMapper mapper = new ObjectMapper();

    public ManifestApplyService(ResourceRepository store) {
        this.store = store;
    }

    @Transactional
    public void apply(ParsedManifest pm, String namespace, String appliedBy) throws Exception {
        var manifest = pm.manifest();
        String kind  = manifest.kind();
        String name  = manifest.metadata().name();
        String ns    = manifest.metadata().namespace() != null ? manifest.metadata().namespace() : namespace;

        String specJson   = mapper.writeValueAsString(manifest.spec());
        String labelsJson = mapper.writeValueAsString(manifest.metadata().labels());

        var entity = store.findByKindAndNamespaceAndName(kind, ns, name)
            .orElse(new ResourceEntity(UlidGenerator.generate(), kind, ns, name, specJson, labelsJson, appliedBy));
        entity.setSpecJson(specJson);
        entity.setLabelsJson(labelsJson);
        entity.setAppliedBy(appliedBy);
        store.save(entity);
    }
}
