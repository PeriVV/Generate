package eval.oss.lang.case003;

/** Adapted from org.apache.commons.lang3.StringUtils 3.14.0. */
public class StringUtilsFixture {
    public boolean isBlank(CharSequence value) {
        if (value == null || value.length() == 0) return true;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) return false;
        }
        return true;
    }
}
