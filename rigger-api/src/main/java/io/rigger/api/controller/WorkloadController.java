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
import io.rigger.security.crypto.SecretEncryptor;
import io.rigger.security.rbac.*;
import io.rigger.store.entity.ResourceEntity;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.swarm.adapter.ConfigAdapter;
import io.rigger.swarm.adapter.SecretAdapter;
import io.rigger.swarm.adapter.ServiceAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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

    private static final Logger log = LoggerFactory.getLogger(WorkloadController.class);

    private final ResourceRepository   store;
    private final ManifestParser       parser;
    private final ManifestSchemaValidator schemaValidator;
    private final RbacPolicyEngine     rbac;
    private final AuditService         audit;
    private final RiggerEventBus       eventBus;
    private final ServiceAdapter       swarmAdapter;
    private final ConfigAdapter        configAdapter;
    private final SecretAdapter        secretAdapter;
    private final SecretEncryptor      secretEncryptor;
    private final ObjectMapper         mapper = new ObjectMapper();

    public WorkloadController(ResourceRepository store, ManifestParser parser,
                               ManifestSchemaValidator schemaValidator,
                               RbacPolicyEngine rbac, AuditService audit,
                               RiggerEventBus eventBus, ServiceAdapter swarmAdapter,
                               ConfigAdapter configAdapter, SecretAdapter secretAdapter,
                               SecretEncryptor secretEncryptor) {
        this.store = store; this.parser = parser;
        this.schemaValidator = schemaValidator;
        this.rbac = rbac; this.audit = audit;
        this.eventBus = eventBus; this.swarmAdapter = swarmAdapter;
        this.configAdapter = configAdapter; this.secretAdapter = secretAdapter;
        this.secretEncryptor = secretEncryptor;
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
            schemaValidator.validateOrThrow(manifest.kind(), pm.rawYaml());

            Object spec = manifest.spec();
            if ("Secret".equals(manifest.kind()) && spec instanceof SecretSpec secretSpec) {
                spec = encryptSecretData(secretSpec);
            }

            String specJson  = mapper.writeValueAsString(spec);
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
            String auditAfterState = req.dryRun() ? null
                : "Secret".equals(manifest.kind()) ? "<redacted-secret-data>" : specJson;
            audit.recordSuccess(ctx, AuditAction.APPLY, manifest.kind(), manifest.metadata().name(),
                exists ? "previous" : null, auditAfterState);

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

    @GetMapping("/pods")
    public ResponseEntity<List<PodResponse>> listPods(
            @PathVariable String namespace, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "get", "Pod");

        var pods = new ArrayList<PodResponse>();
        for (var deployment : store.findByKindAndNamespace("Deployment", namespace)) {
            var svc = swarmAdapter.find(namespace, deployment.getName());
            if (svc.isEmpty()) continue;
            for (var task : swarmAdapter.listTasks(svc.get().getId())) {
                var status = task.getStatus();
                var containerSpec = task.getSpec() != null ? task.getSpec().getContainerSpec() : null;
                pods.add(new PodResponse(
                    task.getId(), namespace, deployment.getName(),
                    containerSpec != null ? containerSpec.getImage() : null,
                    task.getNodeId(),
                    status != null && status.getState() != null ? status.getState().name() : "UNKNOWN",
                    task.getDesiredState() != null ? task.getDesiredState().name() : "UNKNOWN",
                    status != null ? status.getMessage() : null,
                    task.getCreatedAt()));
            }
        }
        return ResponseEntity.ok(pods);
    }

    @GetMapping("/pods/{podName}/logs")
    public ResponseEntity<StreamingResponseBody> podLogs(
            @PathVariable String namespace, @PathVariable String podName,
            @RequestParam(defaultValue = "false") boolean follow,
            HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "logs", "Pod");

        String containerId = resolveContainerId(namespace, podName);
        if (containerId == null) {
            throw new ResourceNotFoundException(ResourceKind.POD, namespace, podName);
        }

        StreamingResponseBody body = out -> swarmAdapter.streamLogs(containerId, follow, out);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body);
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

    @DeleteMapping("/services/{name}")
    public ResponseEntity<Void> deleteService(
            @PathVariable String namespace, @PathVariable String name, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "delete", "Service");

        store.findByKindAndNamespaceAndName("Service", namespace, name)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.SERVICE, namespace, name));

        // No Swarm-side object to clean up yet — ServiceController.reconcile() is still a
        // no-op (Service resources aren't pushed to Swarm). Once it reconciles real published
        // ports, this delete should also revert those on the underlying Deployment's service.
        store.deleteByKindAndNamespaceAndName("Service", namespace, name);

        var ref = new ResourceRef(ResourceKind.SERVICE, namespace, name);
        eventBus.publish(new ResourceDeletedEvent(ref, ctx.identityName()));
        audit.recordSuccess(ctx, AuditAction.DELETE, "Service", name, null, null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/configmaps/{name}")
    public ResponseEntity<Void> deleteConfigMap(
            @PathVariable String namespace, @PathVariable String name, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "delete", "ConfigMap");

        store.findByKindAndNamespaceAndName("ConfigMap", namespace, name)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.CONFIG_MAP, namespace, name));

        try {
            configAdapter.find(namespace, name).ifPresent(cfg -> configAdapter.delete(cfg.getId()));
        } catch (Exception e) {
            // Don't let a Swarm-side lookup/delete failure block removing Rigger's own record —
            // the next reconciliation pass (or a manual `docker config rm`) can clean up Swarm.
            log.warn("Could not remove Swarm Config for {}/{}: {}", namespace, name, e.getMessage());
        }
        store.deleteByKindAndNamespaceAndName("ConfigMap", namespace, name);

        var ref = new ResourceRef(ResourceKind.CONFIG_MAP, namespace, name);
        eventBus.publish(new ResourceDeletedEvent(ref, ctx.identityName()));
        audit.recordSuccess(ctx, AuditAction.DELETE, "ConfigMap", name, null, null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/secrets/{name}")
    public ResponseEntity<Void> deleteSecret(
            @PathVariable String namespace, @PathVariable String name, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "delete", "Secret");

        store.findByKindAndNamespaceAndName("Secret", namespace, name)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.SECRET, namespace, name));

        secretAdapter.find(namespace, name).ifPresent(s -> secretAdapter.delete(s.getId()));
        store.deleteByKindAndNamespaceAndName("Secret", namespace, name);

        var ref = new ResourceRef(ResourceKind.SECRET, namespace, name);
        eventBus.publish(new ResourceDeletedEvent(ref, ctx.identityName()));
        // Never carries secret data — beforeState/afterState are already null for deletes.
        audit.recordSuccess(ctx, AuditAction.DELETE, "Secret", name, null, null);
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

    /** Finds the container ID for a task (pod) by scanning this namespace's Deployments. */
    private String resolveContainerId(String namespace, String podName) {
        for (var deployment : store.findByKindAndNamespace("Deployment", namespace)) {
            var svc = swarmAdapter.find(namespace, deployment.getName());
            if (svc.isEmpty()) continue;
            for (var task : swarmAdapter.listTasks(svc.get().getId())) {
                if (task.getId().equals(podName)) {
                    var containerStatus = task.getStatus() != null ? task.getStatus().getContainerStatus() : null;
                    return containerStatus != null ? containerStatus.getContainerID() : null;
                }
            }
        }
        return null;
    }

    /** Encrypts every value in a Secret's data map. Never called on read paths. */
    private SecretSpec encryptSecretData(SecretSpec spec) {
        if (spec.data() == null || spec.data().isEmpty()) return spec;
        var encrypted = new LinkedHashMap<String, String>();
        spec.data().forEach((key, value) -> encrypted.put(key, secretEncryptor.encrypt(value)));
        return new SecretSpec(encrypted, spec.vaultRef());
    }

    private ResourceResponse toResponse(ResourceEntity e) {
        Object spec = null;
        try { spec = mapper.readValue(e.getSpecJson(), Object.class); } catch (Exception ignored) {}
        return new ResourceResponse(e.getKind(), e.getName(), e.getNamespace(),
            spec, Map.of(), e.getAppliedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
