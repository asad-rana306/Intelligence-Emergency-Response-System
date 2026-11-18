package com.iers.auth.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNormalizerTest {

    private final PhoneNormalizer normalizer = new PhoneNormalizer("92");

    @ParameterizedTest
    @ValueSource(strings = {
            "+923001234567",
            "0300 1234567",
            "0300-1234567",
            "(0300) 123 4567",
            "00923001234567",
            "923001234567",
            " +92 300 123 4567 "
    })
    @DisplayName("Pakistani numbers in many forms all become +923001234567")
    void normalizesPakistaniNumbers(String raw) {
        assertThat(normalizer.normalize(raw)).isEqualTo("+923001234567");
    }

    @Test
    @DisplayName("other countries keep their own code")
    void otherCountries() {
        assertThat(normalizer.normalize("+1 (415) 555-2671")).isEqualTo("+14155552671");
        assertThat(normalizer.normalize("0044 7911 123456")).isEqualTo("+447911123456");
    }

    @Test
    @DisplayName("default country code is configurable (and a '+' in the setting is ignored)")
    void configurableCountryCode() {
        assertThat(new PhoneNormalizer("+44").normalize("07911123456")).isEqualTo("+447911123456");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc", "12ab34", "123", "+0123456789", "+9230012345678901234", "++923001234567"})
    @DisplayName("not a phone number: refused with a clear message")
    void rejectsBadNumbers(String raw) {
        assertThatThrownBy(() -> normalizer.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
        assertThat(normalizer.isValid(raw)).isFalse();
    }

    @Test
    @DisplayName("isValid is true for a good number")
    void isValid() {
        assertThat(normalizer.isValid("03001234567")).isTrue();
    }
}
