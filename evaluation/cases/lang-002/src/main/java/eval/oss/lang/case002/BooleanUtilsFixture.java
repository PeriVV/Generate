package eval.oss.lang.case002;

/** Adapted from org.apache.commons.lang3.BooleanUtils 3.14.0. */
public class BooleanUtilsFixture {
    public int toInteger(Boolean value, int trueValue, int falseValue, int nullValue) {
        if (value == null) return nullValue;
        return value ? trueValue : falseValue;
    }
}
