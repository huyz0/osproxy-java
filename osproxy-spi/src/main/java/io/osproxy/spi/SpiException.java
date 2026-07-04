package io.osproxy.spi;

import io.osproxy.core.EndpointKind;
import io.osproxy.core.ErrorCode;
import io.osproxy.core.PartitionId;

/**
 * The exhaustive failure taxonomy of the SPI, mirroring the Rust proxy's
 * {@code SpiError}. Sealed so the engine's mapping to HTTP statuses is
 * checked complete at compile time. Messages are shape-only: they name
 * sources and kinds, never tenant values.
 */
public abstract sealed class SpiException extends Exception {

    private SpiException(String message) {
        super(message);
    }

    /** The stable wire code the engine serves this failure as. */
    public abstract ErrorCode errorCode();

    /** No configured source yielded a partition key. */
    public static final class PartitionUnresolved extends SpiException {
        public PartitionUnresolved() {
            super("no configured source yielded a partition key");
        }

        @Override
        public ErrorCode errorCode() {
            return ErrorCode.PARTITION_UNRESOLVED;
        }
    }

    /** The partition resolved but has no placement. */
    public static final class PlacementMissing extends SpiException {
        private final PartitionId partition;

        public PlacementMissing(PartitionId partition) {
            super("no placement for the resolved partition");
            this.partition = partition;
        }

        public PartitionId partition() {
            return partition;
        }

        @Override
        public ErrorCode errorCode() {
            return ErrorCode.PLACEMENT_MISSING;
        }
    }

    /** The placement backend failed; {@code retryable} says whether to retry. */
    public static final class PlacementBackend extends SpiException {
        private final boolean retryable;

        public PlacementBackend(boolean retryable) {
            super("placement backend unavailable");
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }

        @Override
        public ErrorCode errorCode() {
            return ErrorCode.PLACEMENT_BACKEND_UNAVAILABLE;
        }
    }

    /** The SPI does not handle this endpoint in its mode. */
    public static final class UnsupportedEndpoint extends SpiException {
        private final EndpointKind endpoint;

        public UnsupportedEndpoint(EndpointKind endpoint) {
            super("endpoint not supported by this tenancy: " + endpoint.wireName());
            this.endpoint = endpoint;
        }

        public EndpointKind endpoint() {
            return endpoint;
        }

        @Override
        public ErrorCode errorCode() {
            return ErrorCode.UNSUPPORTED_ENDPOINT;
        }
    }

    /** A principal attribute needed for resolution/injection is absent. */
    public static final class PrincipalAttrMissing extends SpiException {
        public PrincipalAttrMissing(String attribute) {
            super("principal attribute missing: " + attribute);
        }

        @Override
        public ErrorCode errorCode() {
            return ErrorCode.PARTITION_UNRESOLVED;
        }
    }

    /** A request header needed for resolution/injection is absent. */
    public static final class HeaderMissing extends SpiException {
        public HeaderMissing(String header) {
            super("request header missing: " + header);
        }

        @Override
        public ErrorCode errorCode() {
            return ErrorCode.PARTITION_UNRESOLVED;
        }
    }

    /**
     * A SharedIndex placement whose doc-id rule does not embed the partition:
     * fail-closed, because physical ids could collide across tenants.
     */
    public static final class IdRuleMissingPartition extends SpiException {
        public IdRuleMissingPartition() {
            super("shared-index doc-id rule must reference {partition}");
        }

        @Override
        public ErrorCode errorCode() {
            return ErrorCode.UNSUPPORTED_ENDPOINT;
        }
    }
}
