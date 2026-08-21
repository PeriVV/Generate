package eval.oss.jsoup.case002;

import java.util.Locale;

/** Adapted from org.jsoup.internal.Normalizer in jsoup 1.17.2. */
public class NormalizerFixture {
    public String normalize(String input) {
        if (input == null) return "";
        return input.trim().toLowerCase(Locale.ENGLISH);
    }
}
