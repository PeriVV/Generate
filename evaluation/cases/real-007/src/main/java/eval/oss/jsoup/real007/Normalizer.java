/*
 * Vendored for UnitMind evaluation from https://github.com/jhy/jsoup @ jsoup-1.17.2.
 * Original path: src/main/java/org/jsoup/internal/Normalizer.java
 * Original package `org.jsoup.internal` rewritten to `eval.oss.jsoup.real007` for per-case isolation; no other changes.
 * License: MIT — see evaluation/licenses/jsoup-MIT.txt.
 */
package eval.oss.jsoup.real007;

import java.util.Locale;

/**
 * Util methods for normalizing strings. Jsoup internal use only, please don't depend on this API.
 */
public final class Normalizer {

    /** Drops the input string to lower case. */
    public static String lowerCase(final String input) {
        return input != null ? input.toLowerCase(Locale.ENGLISH) : "";
    }

    /** Lower-cases and trims the input string. */
    public static String normalize(final String input) {
        return lowerCase(input).trim();
    }

    /** If a string literal, just lower case the string; otherwise lower-case and trim. */
    public static String normalize(final String input, boolean isStringLiteral) {
        return isStringLiteral ? lowerCase(input) : normalize(input);
    }
}
