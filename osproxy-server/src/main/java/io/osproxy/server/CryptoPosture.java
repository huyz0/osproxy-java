package io.osproxy.server;

import java.security.Provider;
import java.security.Security;
import java.util.List;

/**
 * The FIPS crypto posture (the Rust project's M6 analog). Two halves:
 *
 * <ul>
 *   <li>{@link #APPROVED_PROTOCOLS}/{@link #APPROVED_SUITES}: the TLS
 *       listener is always pinned to the FIPS-approved set — TLS 1.2/1.3
 *       AES-GCM suites only (no CHACHA20, no CBC), harmless for non-FIPS
 *       deployments and mandatory for FIPS ones.
 *   <li>{@link #requireFipsProvider()}: with {@code osproxy.fips: true} the
 *       server refuses to boot unless a FIPS-validated JCE provider (e.g.
 *       BouncyCastle FIPS, or an NSS-backed SunPKCS11) is installed ahead of
 *       the stock providers. The validated module is a deployment artifact —
 *       exactly as the Rust build links aws-lc-rs — never bundled here.
 * </ul>
 */
public final class CryptoPosture {

    /** FIPS-approved TLS protocol versions. */
    public static final List<String> APPROVED_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

    /** FIPS-approved cipher suites (AES-GCM only; TLS 1.3 + 1.2 ECDHE). */
    public static final List<String> APPROVED_SUITES = List.of(
            // TLS 1.3
            "TLS_AES_256_GCM_SHA384",
            "TLS_AES_128_GCM_SHA256",
            // TLS 1.2 ECDHE (forward secrecy, GCM only)
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");

    /** Provider-name markers of FIPS-validated JCE modules. */
    private static final List<String> FIPS_PROVIDER_MARKERS =
            List.of("BCFIPS", "SunPKCS11-NSS-FIPS");

    private CryptoPosture() {}

    /** The FIPS mode is engaged but no validated module is present. */
    public static final class FipsNotEngaged extends IllegalStateException {
        FipsNotEngaged(String message) {
            super(message);
        }
    }

    /**
     * Fails loud unless a FIPS-validated provider is installed. Called at
     * startup when {@code osproxy.fips} is set: a proxy that silently falls
     * back to non-validated crypto is worse than one that refuses to boot.
     */
    public static void requireFipsProvider() {
        if (installedFipsProvider() == null) {
            throw new FipsNotEngaged(
                    "osproxy.fips is set but no FIPS-validated JCE provider is installed "
                            + "(expected one of " + FIPS_PROVIDER_MARKERS + "); install the "
                            + "validated module (e.g. bc-fips) and register it in java.security");
        }
    }

    /** The installed FIPS provider's name, or null. */
    public static String installedFipsProvider() {
        for (Provider provider : Security.getProviders()) {
            for (String marker : FIPS_PROVIDER_MARKERS) {
                if (provider.getName().contains(marker)) {
                    return provider.getName();
                }
            }
        }
        return null;
    }
}
