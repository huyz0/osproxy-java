package io.osproxy.spi;

import io.osproxy.core.Epoch;
import io.osproxy.core.PartitionId;
import io.osproxy.core.Target;

/**
 * The complete routing decision for one request: where it goes, how the body
 * is transformed, and the epoch the decision was derived under. Produced by
 * the {@code TenancyRouter} from a {@link TenancySpi}.
 */
public record RouteDecision(
        Target target, PartitionId partition, BodyTransform transform, Epoch epoch) {
    public RouteDecision {
        if (target == null || partition == null || transform == null || epoch == null) {
            throw new IllegalArgumentException("decision fields must be non-null");
        }
    }
}
