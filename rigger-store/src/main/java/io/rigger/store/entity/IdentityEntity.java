package io.rigger.store.entity;

import io.rigger.core.domain.security.RiggerRole;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for the identity/user registry.
 * Name is the natural key — lookups are case-insensitive (see IdentityRepository).
 */
@Entity
@Table(name = "identities")
public class IdentityEntity {

    @Id
    @Column(nullable = false, unique = true)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiggerRole role;

    @Column
    private String namespace;

    @Column(name = "cert_serial")
    private String certSerial;

    @Column(nullable = false, columnDefinition = "TEXT")
    private Instant createdAt;

    @Column(columnDefinition = "TEXT")
    private Instant revokedAt;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "password_hash", columnDefinition = "TEXT")
    private String passwordHash;

    protected IdentityEntity() {}

    public IdentityEntity(String id, String name, RiggerRole role, String namespace,
                           String passwordHash) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.namespace = namespace;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public RiggerRole getRole() { return role; }
    public String getNamespace() { return namespace; }
    public String getCertSerial() { return certSerial; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
