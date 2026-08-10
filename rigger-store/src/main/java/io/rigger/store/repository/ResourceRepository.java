package io.rigger.store.repository;

import io.rigger.store.entity.ResourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * Repository for all workload resources (Deployment, Service, ConfigMap, Secret, Pod).
 * Resources are identified by the triple (kind, namespace, name).
 */
@Repository
public interface ResourceRepository extends JpaRepository<ResourceEntity, String> {

    Optional<ResourceEntity> findByKindAndNamespaceAndName(String kind, String namespace, String name);

    List<ResourceEntity> findByKindAndNamespace(String kind, String namespace);

    List<ResourceEntity> findByNamespace(String namespace);

    boolean existsByKindAndNamespaceAndName(String kind, String namespace, String name);

    @Transactional
    void deleteByKindAndNamespaceAndName(String kind, String namespace, String name);

    @Query("SELECT r FROM ResourceEntity r WHERE r.kind = :kind")
    List<ResourceEntity> findAllByKind(@Param("kind") String kind);

    /** Distinct namespaces that currently hold at least one resource — drives the console's namespace picker. */
    @Query("SELECT DISTINCT r.namespace FROM ResourceEntity r ORDER BY r.namespace")
    List<String> findDistinctNamespaces();
}