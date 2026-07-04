package io.osproxy.server;

import io.osproxy.engine.CursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The reference {@link CursorCodec}: seals {@code cluster|partition|upstreamId}
 * with HMAC-SHA256 under an operator-configured key. Wire form is
 * {@code base64url(payload).base64url(mac)}; verification is constant-time
 * and a forged or truncated envelope decodes to empty (fail closed).
 */
public final class HmacCursorCodec implements CursorCodec {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public HmacCursorCodec(String key) {
        if (key == null || key.length() < 16) {
            throw new IllegalArgumentException("cursor affinity key must be >= 16 chars");
        }
        this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @Override
    public String encode(String cluster, String partition, String upstreamId) {
        byte[] payload = (cluster + "|" + partition + "|" + upstreamId)
                .getBytes(StandardCharsets.UTF_8);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString(payload) + "." + b64.encodeToString(mac(payload));
    }

    @Override
    public Optional<Decoded> decode(String wireId) {
        int dot = wireId.indexOf('.');
        if (dot <= 0 || dot == wireId.length() - 1) {
            return Optional.empty();
        }
        byte[] payload;
        byte[] presented;
        try {
            payload = Base64.getUrlDecoder().decode(wireId.substring(0, dot));
            presented = Base64.getUrlDecoder().decode(wireId.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(mac(payload), presented)) {
            return Optional.empty();
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        int first = text.indexOf('|');
        int second = first < 0 ? -1 : text.indexOf('|', first + 1);
        if (first <= 0 || second <= first + 1 || second == text.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new Decoded(
                text.substring(0, first),
                text.substring(first + 1, second),
                text.substring(second + 1)));
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
