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
 *   <li>{@link #engageFips()}: with {@code osproxy.fips: true} the bundled
 *       BouncyCastle FIPS module (the CMVP-validated BC-FIPS 2.1 line) is
 *       switched to approved-only mode and registered ahead of the stock
 *       providers, so every JCE lookup resolves to validated crypto. Boot
 *       fails loud if the module cannot engage — a proxy that silently
 *       falls back to non-validated crypto is worse than one that refuses
 *       to start.
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

    /** The BC-FIPS provider name. */
    public static final String BCFIPS = "BCFIPS";

    private CryptoPosture() {}

    /** FIPS mode was requested but could not be engaged. */
    public static final class FipsNotEngaged extends IllegalStateException {
        FipsNotEngaged(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Engages FIPS mode: approved-only algorithms globally, the validated
     * provider first in line. Idempotent; throws {@link FipsNotEngaged} if
     * the module refuses (e.g. its power-on self-tests fail).
     */
    public static synchronized void engageFips() {
        // Approved-only must be the process default before the module is
        // touched, so no thread can slip a non-approved primitive through.
        System.setProperty("org.bouncycastle.fips.approved_only", "true");
        try {
            if (Security.getProvider(BCFIPS) == null) {
                Security.insertProviderAt(
                        new org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider(), 1);
            }
            if (!org.bouncycastle.crypto.CryptoServicesRegistrar.isInApprovedOnlyMode()) {
                throw new FipsNotEngaged(
                        "BC-FIPS registered but approved-only mode is not engaged", null);
            }
        } catch (RuntimeException e) {
            throw e instanceof FipsNotEngaged fips ? fips : new FipsNotEngaged(
                    "the BC-FIPS module failed to engage (self-tests?)", e);
        }
    }

    /** The installed FIPS provider's name, or null when not engaged. */
    public static String installedFipsProvider() {
        Provider provider = Security.getProvider(BCFIPS);
        return provider != null ? provider.getName() : null;
    }
}
