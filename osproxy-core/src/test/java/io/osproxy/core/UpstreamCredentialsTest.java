package io.osproxy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UpstreamCredentialsTest {

    @Test
    void basicEncodesUserAndPasswordAsAuthorizationHeader() {
        var creds = UpstreamCredentials.basic("svc", "s3cret");
        assertThat(creds.headerName()).isEqualTo("Authorization");
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "svc:s3cret".getBytes(StandardCharsets.UTF_8));
        assertThat(creds.headerValue()).isEqualTo(expected);
    }

    @Test
    void bearerWrapsTheTokenAsAuthorizationHeader() {
        var creds = UpstreamCredentials.bearer("abc123");
        assertThat(creds.headerName()).isEqualTo("Authorization");
        assertThat(creds.headerValue()).isEqualTo("Bearer abc123");
    }

    @Test
    void rejectsBlankHeaderNameOrValue() {
        assertThatThrownBy(() -> new UpstreamCredentials("", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UpstreamCredentials("Authorization", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UpstreamCredentials(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCustomHeaderIsSupportedForNonAuthorizationSchemes() {
        var creds = new UpstreamCredentials("x-api-key", "k-1");
        assertThat(creds.headerName()).isEqualTo("x-api-key");
        assertThat(creds.headerValue()).isEqualTo("k-1");
    }
}
