package io.osproxy.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The client-to-upstream header forwarding policy.
 *
 * <p>When the proxy forwards a request to a cluster it rebuilds the request
 * from scratch, so by default only the headers it manages (content type,
 * trace) reach the upstream. For a sidecar/transparent deployment that is
 * too lossy: the client's own headers (custom routing hints, {@code
 * Authorization}, vendor tracing like B3, …) should travel through. This
 * computes the forwarded set: every client header except the ones that are
 * unsafe to relay verbatim.
 *
 * <p>Two strip lists: a mandatory, non-configurable set (hop-by-hop headers
 * per RFC 9110 §7.6.1, plus {@code host}/{@code content-length}/{@code
 * accept-encoding} because the proxy re-frames the request and does not
 * negotiate a transfer-coding it cannot round-trip), and a configured deny
 * list ({@code deny}), an operator's case-insensitive list on top of the
 * mandatory set. Empty by default (pass-all, the sidecar-trust default).
 */
public record ForwardPolicy(boolean enabled, List<String> deny) {

    public ForwardPolicy {
        deny = List.copyOf(deny);
    }

    private static final List<String> NEVER_FORWARD = List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
            "host", "content-length",
            "accept-encoding");

    /** The default sidecar policy: forward every client header (minus the
     * mandatory hop-by-hop/framing set), nothing extra denied. */
    public static ForwardPolicy passAll() {
        return new ForwardPolicy(true, List.of());
    }

    /** The default: forwarding off (the pre-existing minimal behavior). */
    public static ForwardPolicy disabled() {
        return new ForwardPolicy(false, List.of());
    }

    private static boolean isNeverForwarded(String name) {
        for (String h : NEVER_FORWARD) {
            if (h.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes the headers to forward upstream from the raw client headers.
     * Empty when forwarding is disabled. Hop-by-hop/framing headers and any
     * in the configured deny list are dropped.
     */
    public List<Map.Entry<String, String>> forwardSet(List<Map.Entry<String, String>> client) {
        if (!enabled) {
            return List.of();
        }
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> header : client) {
            String name = header.getKey();
            if (isNeverForwarded(name)) {
                continue;
            }
            boolean denied = false;
            for (String d : deny) {
                if (d.equalsIgnoreCase(name)) {
                    denied = true;
                    break;
                }
            }
            if (!denied) {
                out.add(header);
            }
        }
        return out;
    }
}
