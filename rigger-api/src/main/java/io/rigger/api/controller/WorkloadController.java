package io.rigger.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.api.dto.*;
import io.rigger.api.stream.SseLineFramingOutputStream;
import io.rigger.core.domain.resource.*;
import io.rigger.core.domain.security.*;
import io.rigger.core.exception.*;
import io.rigger.core.util.UlidGenerator;
import io.rigger.events.bus.RiggerEventBus;
import io.rigger.events.model.*;
import io.rigger.manifest.converter.ComposeConverter;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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
    private final ComposeConverter     composeConverter;
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
                               ComposeConverter composeConverter,
                               ManifestSchemaValidator schemaValidator,
                               RbacPolicyEngine rbac, AuditService audit,
                               RiggerEventBus eventBus, ServiceAdapter swarmAdapter,
                               ConfigAdapter configAdapter, SecretAdapter secretAdapter,
                               SecretEncryptor secretEncryptor) {
        this.store = store; this.parser = parser;
        this.composeConverter = composeConverter;
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
            @RequestParam(defaultValue = "false") boolean force,
            HttpServletRequest httpReq) throws Exception {

        var ctx = ctx(httpReq, namespace);
        rbac.authorize(ctx, "apply", "Deployment");

        // docker-compose input is converted to Rigger manifests before anything else, so the rest
        // of this method doesn't care which format arrived. Detection is content-based: a Compose
        // file has a top-level services map and no apiVersion/kind.
        boolean isCompose = composeConverter.isCompose(req.manifest());
        List<ParsedManifest> parsed;
        List<ComposeConverter.Issue> composeIssues = List.of();
        if (isCompose) {
            var conversion = composeConverter.convertString(req.manifest(), namespace, "compose");
            composeIssues = conversion.issues();
            // Applying a Compose file used to succeed while quietly discarding volumes, command,
            // healthcheck and more — the caller was told "created" about a Deployment that ran a
            // different process with none of its data. An ERROR-level issue means exactly that
            // class of loss, so it stops the apply and names every offending path. `force=true` is
            // the deliberate override; there is no implicit one.
            if (conversion.hasErrors() && !force) {
                var violations = new ArrayList<String>();
                conversion.errors().forEach(i -> violations.add(i.path() + ": " + i.message()));
                violations.add("Use POST /convert (or `riggerctl convert -f <file>`) to see the "
                    + "generated rigger.io/v1 YAML and fix these, or repeat this request with "
                    + "?force=true to apply anyway, accepting the loss.");
                throw new ManifestValidationException(violations);
            }
            composeIssues.forEach(i -> log.warn("compose conversion {}", i));
            parsed = conversion.manifests();
        } else {
            parsed = parser.parseString(req.manifest(), "api");
        }
        var results = new ArrayList<Map<String, Object>>();

        for (var pm : parsed) {
            var manifest = pm.manifest();
            rbac.authorize(ctx, "apply", manifest.kind());
            // Converted manifests have no source YAML of their own to validate — the converter
            // builds domain records directly, which their own constructors already validate.
            if (pm.rawYaml() != null) {
                schemaValidator.validateOrThrow(manifest.kind(), pm.rawYaml());
            }

            Object spec = manifest.spec();
            if ("Secret".equals(manifest.kind()) && spec instanceof SecretSpec secretSpec) {
                spec = encryptSecretData(secretSpec);
            }

            String specJson  = mapper.writeValueAsString(spec);
            String labelsJson = mapper.writeValueAsString(manifest.metadata().labels());
            boolean exists   = store.existsByKindAndNamespaceAndName(
                manifest.kind(), namespace, manifest.metadata().name());

            // A dry run validates and reports, and must change nothing: everything above this point
            // (parse, RBAC, schema) is the validation the caller asked for. Previously dryRun only
            // suppressed the audit payload while still persisting, so `riggerctl apply --dry-run`
            // and the console's "validate without applying" both really applied — the resource then
            // got reconciled onto Swarm for good measure.
            if (!req.dryRun()) {
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
                    exists ? "previous" : null,
                    "Secret".equals(manifest.kind()) ? "<redacted-secret-data>" : specJson);
            }

            results.add(Map.of("kind", manifest.kind(), "name", manifest.metadata().name(),
                "namespace", namespace,
                "action", req.dryRun() ? "validated" : exists ? "updated" : "created"));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("applied", results.size());
        body.put("resources", results);
        // Only present for Compose input, and only when something was lost — a caller that sees the
        // key knows the answer isn't the whole story.
        if (!composeIssues.isEmpty()) {
            body.put("composeIssues", composeIssues.stream()
                .map(ConvertResponse.ComposeIssue::from).toList());
        }
        return ResponseEntity.ok(body);
    }

    // ── Convert ────────────────────────────────────────────────────────────

    /**
     * Translates docker-compose input to {@code rigger.io/v1} YAML and reports everything that could
     * not be carried across. <strong>Persists nothing</strong> and touches neither Swarm nor the
     * store — it is a pure function of the body.
     *
     * <p>RBAC: {@code get}/{@code Deployment}, not {@code apply}. Converting is not applying: the
     * response is derived entirely from input the caller already holds, reveals nothing about the
     * cluster, and changes nothing in it. Requiring {@code apply} would mean a VIEWER could not
     * inspect what a Compose file <em>would</em> become — which is precisely the review step this
     * endpoint exists for. The {@code get}/{@code Deployment} pair still forces authentication and
     * still runs the namespace-scope gate in {@link RbacPolicyEngine#authorize}, so a scoped
     * identity cannot convert into someone else's namespace (the namespace is stamped into the
     * generated manifests, so it is not a neutral parameter). It needs no new policy row: DEPLOYER
     * and VIEWER already have it, and GITOPS_AGENT — which applies through the trusted internal path
     * and never previews — deliberately does not.
     */
    @PostMapping("/convert")
    public ResponseEntity<ConvertResponse> convert(
            @PathVariable String namespace,
            @RequestBody ConvertRequest req,
            HttpServletRequest httpReq) throws Exception {

        var ctx = ctx(httpReq, namespace);
        rbac.authorize(ctx, "get", "Deployment");

        String content = req.content();
        if (content == null || content.isBlank()) {
            throw new InvalidRequestException("content: docker-compose YAML must not be empty");
        }
        if (!composeConverter.isCompose(content)) {
            // Distinguishable from "converted to nothing": a rigger.io/v1 manifest needs no
            // conversion, and saying so is more useful than returning an empty result.
            throw new InvalidRequestException(
                "content: not a docker-compose document (expected a top-level 'services' map and no "
                + "apiVersion/kind). A rigger.io/v1 manifest needs no conversion — apply it directly.");
        }

        var conversion = composeConverter.convertString(content, namespace, "convert");
        return ResponseEntity.ok(
            ConvertResponse.from(composeConverter.toYaml(conversion.manifests()), conversion));
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

    /**
     * Plain-text chunked log stream. This is what {@code riggerctl logs} reads (newline-delimited
     * bytes via Okio), so its framing must not change.
     */
    /**
     * Raw newline-delimited log lines, chunked. This is what {@code riggerctl logs} reads.
     */
    @GetMapping("/pods/{podName}/logs")
    public ResponseEntity<StreamingResponseBody> podLogs(
            @PathVariable String namespace, @PathVariable String podName,
            @RequestParam(defaultValue = "false") boolean follow,
            HttpServletRequest req) {

        String containerId = authorizeAndResolveContainer(namespace, podName, req);
        if (containerId == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
            .body(out -> swarmAdapter.streamLogs(containerId, follow, out));
    }

    /**
     * The same logs as Server-Sent Events, for the browser console.
     *
     * <p>A separate path rather than the same path with a different {@code produces}: content
     * negotiation had to break the tie for a wildcard Accept header — which is what riggerctl
     * sends — and chose event-stream, so the CLI started receiving {@code data:} prefixes. Distinct
     * paths make each client's framing unambiguous and independent of header details.
     */
    @GetMapping("/pods/{podName}/logs/stream")
    public ResponseEntity<SseEmitter> podLogsSse(
            @PathVariable String namespace, @PathVariable String podName,
            @RequestParam(defaultValue = "false") boolean follow,
            HttpServletRequest req) {

        String containerId = authorizeAndResolveContainer(namespace, podName, req);
        if (containerId == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(sseLogs(namespace, podName, containerId, follow));
    }

    /**
     * Streams the container's log lines as Server-Sent Events.
     *
     * <p>Uses {@link SseEmitter} rather than writing {@code data:} framing into a
     * {@code StreamingResponseBody}: that sent correct headers but had its streaming thread
     * interrupted immediately, leaving the browser with an open, permanently empty stream and no
     * error to explain it.
     */
    private SseEmitter sseLogs(String namespace, String podName, String containerId, boolean follow) {
        // No timeout: a followed stream should stay open until the client goes away.
        var emitter = new SseEmitter(0L);

        // Docker's log read blocks, so it runs off the request thread; a virtual thread keeps that
        // cheap even with several viewers open.
        Thread.ofVirtual().name("pod-logs-" + podName).start(() -> {
            try (var sink = new SseLineFramingOutputStream(emitter)) {
                swarmAdapter.streamLogs(containerId, follow, sink);
                emitter.complete();
            } catch (Exception e) {
                // A closed tab arrives here as a broken pipe — routine, not a fault.
                log.debug("Log stream for {}/{} ended: {}", namespace, podName, e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * Same logs, re-framed as Server-Sent Events so the browser console can consume them with a
     * native {@code EventSource} instead of hand-rolling a chunked-body reader. Selected by
     * {@code Accept: text/event-stream}; the plain-text variant above is unaffected.
     */
    private String authorizeAndResolveContainer(String namespace, String podName, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "logs", "Pod");
        return resolveContainerId(namespace, podName);
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
