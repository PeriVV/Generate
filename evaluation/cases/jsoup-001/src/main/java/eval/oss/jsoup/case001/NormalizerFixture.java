package eval.oss.jsoup.case001;

import java.util.Locale;

/** Adapted from org.jsoup.internal.Normalizer in jsoup 1.17.2. */
public class NormalizerFixture {
    public String lowerCase(String input) {
        return input != null ? input.toLowerCase(Locale.ENGLISH) : "";
    }
}
