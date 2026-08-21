package eval.oss.lang.case001;

/** Adapted from org.apache.commons.lang3.StringUtils 3.14.0. */
public class StringUtilsFixture {
    public String defaultString(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }
}
