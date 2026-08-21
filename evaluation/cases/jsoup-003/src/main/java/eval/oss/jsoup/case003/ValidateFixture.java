package eval.oss.jsoup.case003;

/** Adapted from org.jsoup.helper.Validate in jsoup 1.17.2. */
public class ValidateFixture {
    public String notEmpty(String value) {
        if (value == null || value.length() == 0) throw new IllegalArgumentException("must not be empty");
        return value;
    }
}
