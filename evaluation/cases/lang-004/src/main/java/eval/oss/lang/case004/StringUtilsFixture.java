package eval.oss.lang.case004;

/** A compact adaptation of StringUtils abbreviation behavior from Commons Lang 3.14.0. */
public class StringUtilsFixture {
    public String abbreviate(String value, int offset, int maxWidth) {
        if (value == null) return null;
        if (maxWidth < 4) throw new IllegalArgumentException("width");
        if (value.length() <= maxWidth) return value;
        if (offset < 0) offset = 0;
        if (offset > value.length()) offset = value.length();
        if (offset <= 4) return value.substring(0, maxWidth - 3) + "...";
        if (maxWidth < 7) throw new IllegalArgumentException("offset width");
        if (offset + maxWidth - 3 < value.length())
            return "..." + value.substring(offset, offset + maxWidth - 3);
        return "..." + value.substring(value.length() - (maxWidth - 3));
    }
}
