package io.rigger.store.repository;

import io.rigger.store.entity.AuditEntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

/**
 * Repository for audit log entries.
 * Provides only read and insert operations — no delete or update.
 * Queries support pagination for the audit log API endpoint.
 */
@Repository
public interface AuditRepository extends JpaRepository<AuditEntryEntity, String> {

    Page<AuditEntryEntity> findByNamespaceOrderByTimestampDesc(String namespace, Pageable pageable);

    Page<AuditEntryEntity> findByIdentityNameOrderByTimestampDesc(String identityName, Pageable pageable);

    Page<AuditEntryEntity> findByActionOrderByTimestampDesc(String action, Pageable pageable);

    @Query("SELECT a FROM AuditEntryEntity a WHERE a.timestamp BETWEEN :from AND :to ORDER BY a.timestamp DESC")
    Page<AuditEntryEntity> findByTimeRange(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    @Query("SELECT a FROM AuditEntryEntity a WHERE a.namespace = :ns AND a.resourceName = :name ORDER BY a.timestamp DESC")
    List<AuditEntryEntity> findResourceHistory(@Param("ns") String ns, @Param("name") String name);
}