package io.rigger.store.repository;

import io.rigger.store.entity.GitOpsConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for the single-row GitOps agent configuration. */
@Repository
public interface GitOpsConfigRepository extends JpaRepository<GitOpsConfigEntity, String> {
}
