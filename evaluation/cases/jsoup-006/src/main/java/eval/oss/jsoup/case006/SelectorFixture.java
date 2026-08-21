package eval.oss.jsoup.case006;

/** A self-contained selector decision shape adapted from jsoup 1.17.2 evaluators. */
public class SelectorFixture {
    public boolean matches(String tag, String expected, boolean hasClass, boolean root) {
        if (tag == null || expected == null) return false;
        if (root && "root".equals(expected)) return true;
        if (expected.startsWith(".")) return hasClass && expected.length() > 1;
        if (expected.equals("*")) return true;
        if (tag.equalsIgnoreCase(expected)) return true;
        if (expected.contains("|")) {
            String local = expected.substring(expected.indexOf('|') + 1);
            return tag.equalsIgnoreCase(local);
        }
        return false;
    }
}
