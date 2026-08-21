package eval.oss.jsoup.case005;

/** Adapted from org.jsoup.internal.StringUtil in jsoup 1.17.2. */
public class StringUtilFixture {
    public boolean isBlank(String value) {
        if (value == null) return true;
        if (value.isEmpty()) return true;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == 160) return false;
            if (!Character.isWhitespace(codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
