package io.rigger.operator.diff;

import com.github.dockerjava.api.model.Service;
import io.rigger.core.domain.resource.ObjectMeta;
import java.util.List;

/**
 * ReconcilePlan variant that holds docker-java {@link Service} objects
 * instead of the generic Rigger model types.
 */
public record DockerJavaReconcilePlan(
        List<ReconcilePlan.DesiredResource> toCreate,
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

    /** Casts toUpdate to the generic form needed by callers. */
    @SuppressWarnings("unchecked")
    public List<UpdatePair> toUpdateDocker() { return toUpdate; }

    /** Returns toDelete as the docker-java type. */
    public List<Service> toDeleteDocker() { return toDelete; }

    public record UpdatePair(Service existing, ObjectMeta meta, Object spec) {}
}
