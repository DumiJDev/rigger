package io.rigger.store.repository;

import io.rigger.core.domain.cluster.NodeRole;
import io.rigger.core.domain.cluster.NodeStatus;
import io.rigger.store.entity.NodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Repository for cluster node state.
 * Node name is the natural key (matches the name in rigger.cluster.yaml).
 */
@Repository
public interface NodeRepository extends JpaRepository<NodeEntity, String> {

    List<NodeEntity> findByClusterName(String clusterName);

    List<NodeEntity> findByRole(NodeRole role);

    List<NodeEntity> findByStatus(NodeStatus status);

    List<NodeEntity> findByClusterNameAndRole(String clusterName, NodeRole role);
}