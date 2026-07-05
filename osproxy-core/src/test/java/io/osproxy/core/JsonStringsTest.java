package io.osproxy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonStringsTest {

    @Test
    void escapesQuotesBackslashesAndCommonControlChars() {
        assertThat(JsonStrings.escape("say \"hi\"")).isEqualTo("say \\\"hi\\\"");
        assertThat(JsonStrings.escape("back\\slash")).isEqualTo("back\\\\slash");
        assertThat(JsonStrings.escape("line\nbreak")).isEqualTo("line\\nbreak");
        assertThat(JsonStrings.escape("cr\rreturn")).isEqualTo("cr\\rreturn");
        assertThat(JsonStrings.escape("tab\ttab")).isEqualTo("tab\\ttab");
    }

    @Test
    void escapesOtherControlCharsAsUnicodeEscapes() {
        String withControlChar = "a" + Character.toString(1) + "b";
        assertThat(JsonStrings.escape(withControlChar)).isEqualTo("a\\u0001b");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        assertThat(JsonStrings.escape("plain text 123")).isEqualTo("plain text 123");
        assertThat(JsonStrings.escape("")).isEqualTo("");
    }
}
