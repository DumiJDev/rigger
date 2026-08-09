package io.rigger.store.repository;

import io.rigger.store.entity.GitOpsStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for GitOps sync state.
 * One record per repository URL.
 */
@Repository
public interface GitOpsStateRepository extends JpaRepository<GitOpsStateEntity, String> {}