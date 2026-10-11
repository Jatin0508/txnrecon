package com.txnrecon.engine;

import java.util.Locale;

/**
 * Utility class that standardizes transaction reference numbers (RRNs) across data sources.
 * Different banking systems often format references differently (e.g., "rrn-1001/a" vs "RRN1001A").
 * Normalizing converts characters to uppercase and strips all non-alphanumeric characters.
 */
public final class ReferenceNormalizer {

    private ReferenceNormalizer() {
        // Prevent instantiation of utility class
    }

    /**
     * Converts the input reference to uppercase and removes every non-alphanumeric character.
     *
     * @param rawReference Raw reference string from CSV or caller
     * @return Normalized alphanumeric uppercase string, or empty string if input is null
     */
    public static String normalize(String rawReference) {
        if (rawReference == null) {
            return "";
        }
        String upper = rawReference.trim().toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
