package eval.cases.classpure002;

public class TextRules {
    public String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase();
    }

    public String prefix(String value, int length) {
        if (length < 0) throw new IllegalArgumentException("length");
        return value.substring(0, Math.min(length, value.length()));
    }

    static boolean same(String left, String right) {
        return normalizeStatic(left).equals(normalizeStatic(right));
    }

    private static String normalizeStatic(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
