package io.rigger.store.repository;

import io.rigger.store.entity.IdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Repository for the identity/user registry.
 * Username lookups are case-insensitive (matches UserStore's prior in-memory behaviour).
 */
@Repository
public interface IdentityRepository extends JpaRepository<IdentityEntity, String> {

    Optional<IdentityEntity> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
