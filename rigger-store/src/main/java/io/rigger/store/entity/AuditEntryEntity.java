package io.rigger.store.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for an audit log entry.
 * This table is append-only: the application never issues DELETE or UPDATE against it.
 * Enforced at the service layer — not a DB constraint, to allow export/archival.
 */
@Entity
@Table(name = "audit_log")
public class AuditEntryEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(nullable = false)
    private String identityName;

    @Column(nullable = false)
    private String identityRole;

    @Column(nullable = false)
    private String action;

    @Column
    private String resourceKind;

    @Column
    private String resourceName;

    @Column
    private String namespace;

    @Column(nullable = false)
    private String sourceIp;

    @Column(nullable = false, columnDefinition = "TEXT")
    private Instant timestamp;

    @Column(nullable = false)
    private String result;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** State before action — secrets are NEVER included. */
    @Column(columnDefinition = "TEXT")
    private String beforeState;

    /** State after action — secrets are NEVER included. */
    @Column(columnDefinition = "TEXT")
    private String afterState;

    protected AuditEntryEntity() {}

    public AuditEntryEntity(String id, String identityName, String identityRole,
                             String action, String resourceKind, String resourceName,
                             String namespace, String sourceIp, Instant timestamp,
                             String result, String errorMessage,
                             String beforeState, String afterState) {
        this.id = id; this.identityName = identityName; this.identityRole = identityRole;
        this.action = action; this.resourceKind = resourceKind; this.resourceName = resourceName;
        this.namespace = namespace; this.sourceIp = sourceIp; this.timestamp = timestamp;
        this.result = result; this.errorMessage = errorMessage;
        this.beforeState = beforeState; this.afterState = afterState;
    }

    public String getId() { return id; }
    public String getIdentityName() { return identityName; }
    public String getIdentityRole() { return identityRole; }
    public String getAction() { return action; }
    public String getResourceKind() { return resourceKind; }
    public String getResourceName() { return resourceName; }
    public String getNamespace() { return namespace; }
    public String getSourceIp() { return sourceIp; }
    public Instant getTimestamp() { return timestamp; }
    public String getResult() { return result; }
    public String getErrorMessage() { return errorMessage; }
    public String getBeforeState() { return beforeState; }
    public String getAfterState() { return afterState; }
}