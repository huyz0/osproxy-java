package io.osproxy.rewrite;

/**
 * A transform refused its input. Every variant is a malformed or
 * un-isolatable request, so the engine maps all of them to a 400-class
 * response; the {@link Kind} keeps the reason programmatic without free-text
 * parsing.
 */
public final class RewriteException extends Exception {

    /** Why the transform refused. */
    public enum Kind {
        /** The body is not valid JSON. */
        INVALID_JSON,
        /** The body is valid JSON but not an object where one is required. */
        NOT_AN_OBJECT,
        /** A referenced path does not resolve to a scalar. */
        PATH_NOT_SCALAR,
        /** The id template uses a placeholder that is not {partition} or {id}/{body.*}. */
        UNSUPPORTED_PLACEHOLDER,
        /** The id template cannot be inverted (not exactly one body placeholder). */
        IRREVERSIBLE_ID_TEMPLATE,
        /** A shared-index query carries a construct that escapes the filter. */
        UNFILTERABLE,
        /** An NDJSON bulk/msearch payload is structurally broken. */
        MALFORMED_MULTI
    }

    private final Kind kind;

    public RewriteException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
