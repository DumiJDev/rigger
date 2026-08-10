package io.rigger.operator.diff;

import com.github.dockerjava.api.model.Service;
import io.rigger.core.domain.resource.ObjectMeta;
import java.util.List;

/**
 * The output of comparing desired state (store) with actual state (Swarm), holding docker-java
 * {@link Service} objects directly.
 *
 * @param toCreate  Resources in the store with no corresponding Swarm service.
 * @param toUpdate  Resources whose spec differs from the Swarm service.
 * @param toDelete  Swarm services with no corresponding resource in the store (drift or manual deletion).
 * @param unchanged Count of resources already in sync.
 */
public record ReconcilePlan(
        List<DesiredResource> toCreate,
        List<UpdatePair> toUpdate,
        List<Service> toDelete,
        int unchanged
) {
    public boolean isEmpty() {
        return toCreate.isEmpty() && toUpdate.isEmpty() && toDelete.isEmpty();
    }

    public int totalChanges() {
        return toCreate.size() + toUpdate.size() + toDelete.size();
    }

    /** A desired resource with no existing Swarm counterpart. */
    public record DesiredResource(ObjectMeta meta, Object spec) {}

    /** An existing Swarm service that needs updating to match the desired spec. */
    public record UpdatePair(Service existing, ObjectMeta meta, Object spec) {}
}
