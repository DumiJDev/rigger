package io.rigger.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.api.dto.*;
import io.rigger.core.domain.resource.*;
import io.rigger.core.domain.security.*;
import io.rigger.core.exception.*;
import io.rigger.core.util.UlidGenerator;
import io.rigger.events.bus.RiggerEventBus;
import io.rigger.events.model.*;
import io.rigger.manifest.parser.*;
import io.rigger.schema.ManifestSchemaValidator;
import io.rigger.security.audit.AuditService;
import io.rigger.security.rbac.*;
import io.rigger.store.entity.ResourceEntity;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.swarm.adapter.ServiceAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for workload resource operations.
 * Base path: /api/v1/namespaces/{namespace}
 *
 * All methods extract {@link RiggerContext} from the request attribute
 * set by {@link io.rigger.security.auth.RiggerAuthenticationFilter}.
 */
@RestController
@RequestMapping("/api/v1/namespaces/{namespace}")
public class WorkloadController {

    private final ResourceRepository   store;
    private final ManifestParser       parser;
    private final ManifestSchemaValidator schemaValidator;
    private final RbacPolicyEngine     rbac;
    private final AuditService         audit;
    private final RiggerEventBus       eventBus;
    private final ServiceAdapter       swarmAdapter;
    private final ObjectMapper         mapper = new ObjectMapper();

    public WorkloadController(ResourceRepository store, ManifestParser parser,
                               ManifestSchemaValidator schemaValidator,
                               RbacPolicyEngine rbac, AuditService audit,
                               RiggerEventBus eventBus, ServiceAdapter swarmAdapter) {
        this.store = store; this.parser = parser;
        this.schemaValidator = schemaValidator;
        this.rbac = rbac; this.audit = audit;
        this.eventBus = eventBus; this.swarmAdapter = swarmAdapter;
    }

    // ── Apply ──────────────────────────────────────────────────────────────

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(
            @PathVariable String namespace,
            @RequestBody ApplyRequest req,
            HttpServletRequest httpReq) throws Exception {

        var ctx = ctx(httpReq, namespace);
        rbac.authorize(ctx, "apply", "Deployment");

        var parsed = parser.parseString(req.manifest(), "api");
        var results = new ArrayList<Map<String, Object>>();

        for (var pm : parsed) {
            var manifest = pm.manifest();
            rbac.authorize(ctx, "apply", manifest.kind());
            schemaValidator.validateOrThrow(manifest.kind(), req.manifest());

            String specJson  = mapper.writeValueAsString(manifest.spec());
            String labelsJson = mapper.writeValueAsString(manifest.metadata().labels());
            boolean exists   = store.existsByKindAndNamespaceAndName(
                manifest.kind(), namespace, manifest.metadata().name());

            var entity = store.findByKindAndNamespaceAndName(manifest.kind(), namespace, manifest.metadata().name())
                .orElse(new ResourceEntity(UlidGenerator.generate(), manifest.kind(),
                    namespace, manifest.metadata().name(), specJson, labelsJson, ctx.identityName()));

            entity.setSpecJson(specJson);
            entity.setLabelsJson(labelsJson);
            entity.setAppliedBy(ctx.identityName());
            store.save(entity);

            var ref = new ResourceRef(ResourceKind.valueOf(manifest.kind().toUpperCase().replace("CONFIGMAP","CONFIG_MAP")),
                namespace, manifest.metadata().name());
            eventBus.publish(new ResourceAppliedEvent(ref, ctx.identityName(), !exists));
            audit.recordSuccess(ctx, AuditAction.APPLY, manifest.kind(), manifest.metadata().name(),
                exists ? "previous" : null, req.dryRun() ? null : specJson);

            results.add(Map.of("kind", manifest.kind(), "name", manifest.metadata().name(),
                "namespace", namespace, "action", exists ? "updated" : "created"));
        }
        return ResponseEntity.ok(Map.of("applied", results.size(), "resources", results));
    }

    // ── List ───────────────────────────────────────────────────────────────

    @GetMapping("/deployments")
    public ResponseEntity<List<ResourceResponse>> listDeployments(
            @PathVariable String namespace, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "get", "Deployment");
        return ResponseEntity.ok(toResponses(store.findByKindAndNamespace("Deployment", namespace)));
    }

    @GetMapping("/services")
    public ResponseEntity<List<ResourceResponse>> listServices(
            @PathVariable String namespace, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "get", "Service");
        return ResponseEntity.ok(toResponses(store.findByKindAndNamespace("Service", namespace)));
    }

    @GetMapping("/configmaps")
    public ResponseEntity<List<ResourceResponse>> listConfigMaps(
            @PathVariable String namespace, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "get", "ConfigMap");
        return ResponseEntity.ok(toResponses(store.findByKindAndNamespace("ConfigMap", namespace)));
    }

    @GetMapping("/secrets")
    public ResponseEntity<List<ResourceResponse>> listSecrets(
            @PathVariable String namespace, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "get", "Secret");
        // Return metadata only — never values
        return ResponseEntity.ok(store.findByKindAndNamespace("Secret", namespace).stream()
            .map(e -> new ResourceResponse(e.getKind(), e.getName(), e.getNamespace(),
                Map.of("keys", "redacted"), Map.of(), e.getAppliedBy(),
                e.getCreatedAt(), e.getUpdatedAt()))
            .toList());
    }

    // ── Get ────────────────────────────────────────────────────────────────

    @GetMapping("/deployments/{name}")
    public ResponseEntity<ResourceResponse> getDeployment(
            @PathVariable String namespace, @PathVariable String name, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "get", "Deployment");
        return store.findByKindAndNamespaceAndName("Deployment", namespace, name)
            .map(e -> ResponseEntity.ok(toResponse(e)))
            .orElse(ResponseEntity.notFound().build());
    }

    // ── Scale ──────────────────────────────────────────────────────────────

    @PostMapping("/deployments/{name}/scale")
    public ResponseEntity<Map<String, Object>> scale(
            @PathVariable String namespace, @PathVariable String name,
            @RequestBody ScaleRequest req, HttpServletRequest httpReq) {
        var ctx = ctx(httpReq, namespace);
        rbac.authorize(ctx, "scale", "Deployment");

        var entity = store.findByKindAndNamespaceAndName("Deployment", namespace, name)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.DEPLOYMENT, namespace, name));

        try {
            var spec = mapper.readValue(entity.getSpecJson(), DeploymentSpec.class);
            var svc  = swarmAdapter.find(namespace, name);
            if (svc.isPresent()) {
                long version = svc.get().getVersion() != null ? svc.get().getVersion().getIndex() : 0;
                swarmAdapter.scale(svc.get().getId(), version, req.replicas());
            }
            int prev = spec.replicas();
            var newSpec = new DeploymentSpec(req.replicas(), spec.selector(), spec.image(),
                spec.env(), spec.resources(), spec.strategy(), spec.hpa(),
                spec.configMapRefs(), spec.secretRefs());
            entity.setSpecJson(mapper.writeValueAsString(newSpec));
            store.save(entity);

            var ref = new ResourceRef(ResourceKind.DEPLOYMENT, namespace, name);
            eventBus.publish(new ResourceScaledEvent(ref, prev, req.replicas(), "manual"));
            audit.recordSuccess(ctx, AuditAction.SCALE, "Deployment", name, null, null);
            return ResponseEntity.ok(Map.of("name", name, "replicas", req.replicas()));
        } catch (Exception e) {
            throw new RuntimeException("Scale failed: " + e.getMessage(), e);
        }
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @DeleteMapping("/deployments/{name}")
    public ResponseEntity<Void> deleteDeployment(
            @PathVariable String namespace, @PathVariable String name, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "delete", "Deployment");

        store.findByKindAndNamespaceAndName("Deployment", namespace, name)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.DEPLOYMENT, namespace, name));

        swarmAdapter.find(namespace, name).ifPresent(svc -> swarmAdapter.delete(svc.getId()));
        store.deleteByKindAndNamespaceAndName("Deployment", namespace, name);

        var ref = new ResourceRef(ResourceKind.DEPLOYMENT, namespace, name);
        eventBus.publish(new ResourceDeletedEvent(ref, ctx.identityName()));
        audit.recordSuccess(ctx, AuditAction.DELETE, "Deployment", name, null, null);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private RiggerContext ctx(HttpServletRequest req, String namespace) {
        var ctx = (RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) throw new io.rigger.core.exception.AccessDeniedException("unknown","any","any");
        return new RiggerContext(ctx.identity(), namespace, ctx.sourceIp(), ctx.timestamp());
    }

    private List<ResourceResponse> toResponses(List<ResourceEntity> entities) {
        return entities.stream().map(this::toResponse).toList();
    }

    private ResourceResponse toResponse(ResourceEntity e) {
        Object spec = null;
        try { spec = mapper.readValue(e.getSpecJson(), Object.class); } catch (Exception ignored) {}
        return new ResourceResponse(e.getKind(), e.getName(), e.getNamespace(),
            spec, Map.of(), e.getAppliedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
