package io.osproxy.core;

/**
 * Minimal JSON string escaping for the hand-built JSON a few shape-only
 * encoders emit (e.g. {@code ExplainDoc}, OTLP's resource-span encoder).
 * Those encoders deliberately avoid a Jackson dependency to stay light
 * (see each module's own dependency list), but "hand-built, no escaping"
 * is only safe as long as every field is proxy-derived and never carries
 * a raw character an attacker could choose — this exists so that
 * assumption doesn't have to hold forever: escape once, here, rather than
 * re-derive the right escape set at every call site.
 */
public final class JsonStrings {

    private JsonStrings() {}

    /** Escapes {@code s} for embedding inside a JSON string literal (no surrounding quotes). */
    public static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
