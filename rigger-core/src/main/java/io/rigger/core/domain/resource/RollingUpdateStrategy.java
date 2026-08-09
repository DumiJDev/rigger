package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rolling update strategy for a Deployment.
 * Maps to Docker Service UpdateConfig.
 *
 * @param maxUnavailable Maximum replicas that can be unavailable during update.
 * @param delaySeconds   Seconds to wait between updating each batch.
 * @param failureAction  Action on update failure: PAUSE | ROLLBACK | CONTINUE.
 */
public record RollingUpdateStrategy(
        @JsonProperty("maxUnavailable") int maxUnavailable,
        @JsonProperty("delaySeconds") int delaySeconds,
        @JsonProperty("failureAction") String failureAction
) {
    public static final RollingUpdateStrategy DEFAULT =
            new RollingUpdateStrategy(1, 10, "PAUSE");

    public RollingUpdateStrategy {
        if (maxUnavailable < 1) maxUnavailable = 1;
        if (delaySeconds < 0) delaySeconds = 0;
        if (failureAction == null) failureAction = "PAUSE";
    }
}
