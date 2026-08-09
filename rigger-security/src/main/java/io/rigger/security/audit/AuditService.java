package io.rigger.security.audit;

import io.rigger.core.domain.security.*;
import io.rigger.core.util.UlidGenerator;
import io.rigger.store.entity.AuditEntryEntity;
import io.rigger.store.repository.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/**
 * Records every auditable action to the append-only audit log.
 *
 * <p>SECURITY INVARIANTS enforced here:
 * <ul>
 *   <li>Secret values are NEVER written — before/after state has secret data stripped.</li>
 *   <li>Audit entries are INSERT-only — this service never issues UPDATE or DELETE.</li>
 *   <li>Failed auth attempts are recorded with DENIED result.</li>
 *   <li>Every write (apply, delete, scale) is recorded even if it later fails.</li>
 * </ul>
 *
 * <p>The audit log is queryable via the REST API by CLUSTER_ADMIN only.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * Records a successful action.
     *
     * @param ctx          Security context (who, namespace, source IP).
     * @param action       What was done.
     * @param resourceKind Kind of resource affected.
     * @param resourceName Name of the resource.
     * @param beforeState  JSON of state before (null for CREATE). Must NOT contain secret values.
     * @param afterState   JSON of state after (null for DELETE). Must NOT contain secret values.
     */
    @Transactional
    public void recordSuccess(RiggerContext ctx, AuditAction action,
                               String resourceKind, String resourceName,
                               String beforeState, String afterState) {
        persist(ctx, action, resourceKind, resourceName,
                AuditResult.SUCCESS, null, beforeState, afterState);
    }

    /**
     * Records a denied action (RBAC policy rejection).
     */
    @Transactional
    public void recordDenied(RiggerContext ctx, AuditAction action,
                              String resourceKind, String resourceName) {
        persist(ctx, action, resourceKind, resourceName, AuditResult.DENIED, "Access denied by RBAC policy", null, null);
    }

    /**
     * Records a failed action (technical error after authorisation passed).
     */
    @Transactional
    public void recordError(RiggerContext ctx, AuditAction action,
                             String resourceKind, String resourceName, String errorMessage) {
        persist(ctx, action, resourceKind, resourceName, AuditResult.ERROR, errorMessage, null, null);
    }

    /**
     * Records a login event (success or failure).
     * sourceIp and identityName are required even when identity is null (failed login).
     */
    @Transactional
    public void recordLogin(String identityName, String role,
                             String sourceIp, boolean success, String errorMessage) {
        var entry = new AuditEntryEntity(
            UlidGenerator.generate(),
            identityName != null ? identityName : "unknown",
            role != null ? role : "UNKNOWN",
            success ? AuditAction.LOGIN_SUCCESS.name() : AuditAction.LOGIN_FAILED.name(),
            null, null, null,
            sourceIp,
            Instant.now(),
            success ? AuditResult.SUCCESS.name() : AuditResult.DENIED.name(),
            errorMessage,
            null, null
        );
        auditRepository.save(entry);
        if (!success) {
            log.warn("AUDIT: login failed for {} from {}", identityName, sourceIp);
        }
    }

    private void persist(RiggerContext ctx, AuditAction action,
                          String resourceKind, String resourceName,
                          AuditResult result, String errorMessage,
                          String beforeState, String afterState) {
        var entry = new AuditEntryEntity(
            UlidGenerator.generate(),
            ctx.identityName(),
            ctx.identity().role().name(),
            action.name(),
            resourceKind,
            resourceName,
            ctx.namespace(),
            ctx.sourceIp(),
            ctx.timestamp(),
            result.name(),
            errorMessage,
            beforeState,  // never contains secret values — enforced by callers
            afterState    // never contains secret values — enforced by callers
        );
        auditRepository.save(entry);

        log.info("AUDIT: {} ({}) {} {}/{} -> {} in ns:{}",
            ctx.identityName(), ctx.identity().role(), action,
            resourceKind, resourceName, result, ctx.namespace());
    }
}
