package io.rigger.store.repository;

import io.rigger.store.entity.EventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** Repository for persisted operational events backing the console's activity feed. */
@Repository
public interface EventRepository extends JpaRepository<EventEntity, String> {

    Page<EventEntity> findByNamespaceOrderByOccurredAtDesc(String namespace, Pageable pageable);

    Page<EventEntity> findAllByOrderByOccurredAtDesc(Pageable pageable);

    /**
     * Prunes events older than the given cutoff. Unlike the audit log this table is safe to
     * trim — it feeds a UI feed, not a security record.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM EventEntity e WHERE e.occurredAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
