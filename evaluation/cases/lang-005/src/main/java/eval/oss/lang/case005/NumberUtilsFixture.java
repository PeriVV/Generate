package eval.oss.lang.case005;

/** Adapted from org.apache.commons.lang3.math.NumberUtils 3.14.0. */
public class NumberUtilsFixture {
    public boolean isParsable(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.charAt(value.length() - 1) == '.') return false;
        int begin = value.charAt(0) == '-' ? 1 : 0;
        if (begin == value.length()) return false;
        int decimalPoints = 0;
        for (int i = begin; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '.') {
                decimalPoints++;
                if (decimalPoints > 1) return false;
            } else if (!Character.isDigit(current)) {
                return false;
            }
        }
        return true;
    }
}
