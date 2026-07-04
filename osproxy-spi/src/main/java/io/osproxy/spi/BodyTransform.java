package io.osproxy.spi;

import java.util.List;

/** The body rewrite a routing decision requests for a write. */
public sealed interface BodyTransform {

    /** Forward the body untouched. */
    record None() implements BodyTransform {
        public static final None INSTANCE = new None();
    }

    /** Inject the given fields into the document. */
    record Inject(List<InjectedField> fields) implements BodyTransform {
        public Inject {
            fields = List.copyOf(fields);
        }
    }

    /** Rewrite the document id per the rule (path/URL level, body untouched). */
    record ConstructId(DocIdRule rule) implements BodyTransform {}

    /** Both inject and construct-id. */
    record Both(List<InjectedField> fields, DocIdRule rule) implements BodyTransform {
        public Both {
            fields = List.copyOf(fields);
        }
    }
}
