package io.osproxy.sink;

import java.util.Optional;

/** One physical document operation, already fully transformed. */
public sealed interface DocOp {

    /** The physical document id. */
    String physicalId();

    /** The OpenSearch routing value, when the id rule pins shards. */
    Optional<String> routing();

    /** Index (upsert) a document. */
    record Index(String physicalId, byte[] doc, Optional<String> routing) implements DocOp {}

    /** Create — like index but fails if the id exists. */
    record Create(String physicalId, byte[] doc, Optional<String> routing) implements DocOp {}

    /** Partial update ({@code doc} is the {@code {"doc":…}} envelope). */
    record Update(String physicalId, byte[] doc, Optional<String> routing) implements DocOp {}

    /** Delete by id. */
    record Delete(String physicalId, Optional<String> routing) implements DocOp {}
}
