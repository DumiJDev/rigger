package io.rigger.operator.diff;

import io.rigger.core.domain.resource.ObjectMeta;
import io.rigger.swarm.model.SwarmService;
import java.util.List;

/**
 * The output of comparing desired state (store) with actual state (Swarm API).
 * Passed to the controller to perform the minimum number of Docker API calls.
 *
 * @param toCreate Resources in the store with no corresponding Swarm service.
 * @param toUpdate Resources in the store whose spec differs from the Swarm service.
 * @param toDelete Swarm services with no corresponding resource in the store (drift or manual deletion).
 * @param unchanged Count of resources that are already in sync.
 */
public record ReconcilePlan(
        List<DesiredResource> toCreate,
        List<UpdatePair> toUpdate,
        List<SwarmService> toDelete,
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

    /** An existing Swarm service that needs to be updated. */
    public record UpdatePair(SwarmService existing, ObjectMeta meta, Object spec) {}
}
