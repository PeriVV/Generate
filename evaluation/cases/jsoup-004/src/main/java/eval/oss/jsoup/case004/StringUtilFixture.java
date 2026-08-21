package eval.oss.jsoup.case004;

/** Adapted from org.jsoup.internal.StringUtil in jsoup 1.17.2. */
public class StringUtilFixture {
    public boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.codePointAt(i))) return false;
        }
        return true;
    }
}
