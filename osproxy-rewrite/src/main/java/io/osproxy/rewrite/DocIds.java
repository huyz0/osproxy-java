package io.osproxy.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * Physical document ids from templates. A template interleaves literal text
 * with {@code {partition}}, {@code {id}} (the client-supplied logical id) and
 * {@code {body.<path>}} (a scalar from the document). For the logical↔physical
 * mapping to be reversible the template must contain exactly one id-bearing
 * placeholder ({@code {id}} or one {@code {body.*}}).
 */
public final class DocIds {

    private DocIds() {}

    /** Expands a template against the partition and the parsed document. */
    public static String constructId(String template, String partition, JsonNode doc)
            throws RewriteException {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);
            int close = template.indexOf('}', open);
            if (close < 0) {
                throw unsupported(template);
            }
            String placeholder = template.substring(open + 1, close);
            if (placeholder.equals("partition")) {
                out.append(partition);
            } else if (placeholder.startsWith("body.")) {
                out.append(Json.extractScalar(doc, placeholder.substring("body.".length())));
            } else {
                throw unsupported(placeholder);
            }
            i = close + 1;
        }
        return out.toString();
    }

    /** Maps a client-visible logical id to the stored physical id. */
    public static String mapLogicalToPhysical(String template, String partition, String logicalId)
            throws RewriteException {
        Frame f = idFrame(template, partition);
        return f.prefix + logicalId + f.suffix;
    }

    /**
     * The inverse: strips the template's literal frame off a physical id.
     * Empty when the id does not fit the frame (e.g. another partition's
     * document), so the caller can fall back rather than mis-report.
     */
    public static Optional<String> mapPhysicalToLogical(
            String template, String partition, String physicalId) throws RewriteException {
        Frame f = idFrame(template, partition);
        if (!physicalId.startsWith(f.prefix) || !physicalId.endsWith(f.suffix)
                || physicalId.length() < f.prefix.length() + f.suffix.length()) {
            return Optional.empty();
        }
        return Optional.of(
                physicalId.substring(f.prefix.length(), physicalId.length() - f.suffix.length()));
    }

    private record Frame(String prefix, String suffix) {}

    /**
     * Renders the literal text around the single id-bearing placeholder, with
     * {@code {partition}} expanded — the frame that makes the mapping
     * invertible.
     */
    private static Frame idFrame(String template, String partition) throws RewriteException {
        StringBuilder prefix = new StringBuilder();
        StringBuilder suffix = new StringBuilder();
        boolean seenIdPlaceholder = false;
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            String literal = open < 0 ? template.substring(i) : template.substring(i, open);
            (seenIdPlaceholder ? suffix : prefix).append(literal);
            if (open < 0) {
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                throw unsupported(template);
            }
            String placeholder = template.substring(open + 1, close);
            if (placeholder.equals("partition")) {
                (seenIdPlaceholder ? suffix : prefix).append(partition);
            } else if (placeholder.equals("id") || placeholder.startsWith("body.")) {
                if (seenIdPlaceholder) {
                    throw new RewriteException(
                            RewriteException.Kind.IRREVERSIBLE_ID_TEMPLATE,
                            "template has more than one id placeholder");
                }
                seenIdPlaceholder = true;
            } else {
                throw unsupported(placeholder);
            }
            i = close + 1;
        }
        if (!seenIdPlaceholder) {
            throw new RewriteException(
                    RewriteException.Kind.IRREVERSIBLE_ID_TEMPLATE,
                    "template has no id placeholder");
        }
        return new Frame(prefix.toString(), suffix.toString());
    }

    private static RewriteException unsupported(String placeholder) {
        return new RewriteException(
                RewriteException.Kind.UNSUPPORTED_PLACEHOLDER,
                "unsupported placeholder: " + placeholder);
    }
}
