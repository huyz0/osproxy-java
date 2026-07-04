package io.osproxy.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/**
 * Injecting isolation fields on the write path and stripping them on the
 * read path — the two halves of the shared-index symmetry. Injection
 * overwrites a client-supplied field of the same name (a client must not be
 * able to spoof its partition marker).
 */
public final class Fields {

    private Fields() {}

    /** Sets each field on the document, overwriting client values. */
    public static void injectFields(ObjectNode doc, Map<String, JsonNode> fields) {
        for (Map.Entry<String, JsonNode> field : fields.entrySet()) {
            doc.set(field.getKey(), field.getValue());
        }
    }

    /** Removes each named field; returns how many were present. */
    public static int stripFields(ObjectNode doc, List<String> names) {
        int stripped = 0;
        for (String name : names) {
            if (doc.remove(name) != null) {
                stripped++;
            }
        }
        return stripped;
    }
}
