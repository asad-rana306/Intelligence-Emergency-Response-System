package com.iers.auth.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Turns the phone numbers people type into one standard form (E.164, like +923001234567).
 *
 * Why: the OTP, the registered phone and the emergency contacts must all use the SAME text,
 * otherwise "0300 1234567" and "+923001234567" look like two different numbers, and SMS
 * gateways only accept the international form.
 *
 * Rules:
 *   - spaces, dashes, dots and brackets are removed;
 *   - "00..."  becomes "+...";
 *   - "0..."   becomes "+{default country code}..." (default 92 = Pakistan);
 *   - digits only (no leading 0 or +) are treated as already having a country code.
 */
@Component
public class PhoneNormalizer {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final String defaultCountryCode;

    public PhoneNormalizer(@Value("${auth.phone.default-country-code:92}") String defaultCountryCode) {
        this.defaultCountryCode = defaultCountryCode.replaceAll("[^0-9]", "");
    }

    /** @throws IllegalArgumentException if the text is not a usable phone number (the API answers 400) */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Phone number is required");
        }
        String s = raw.trim().replaceAll("[\\s\\-().]", "");
        if (s.startsWith("00")) {
            s = "+" + s.substring(2);
        } else if (s.startsWith("0")) {
            s = "+" + defaultCountryCode + s.substring(1);
        } else if (!s.startsWith("+")) {
            s = "+" + s;
        }
        if (!E164.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "Invalid phone number. Use the international form, for example +923001234567");
        }
        return s;
    }

    public boolean isValid(String raw) {
        try {
            normalize(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
