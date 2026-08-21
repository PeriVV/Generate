package eval.oss.lang.case006;

/** A self-contained range operation shaped after Commons Lang Range 3.14.0. */
public class RangeFixture {
    public String intersection(int firstMin, int firstMax, int secondMin, int secondMax, boolean strict) {
        if (firstMin > firstMax || secondMin > secondMax) throw new IllegalArgumentException("range");
        int min = Math.max(firstMin, secondMin);
        int max = Math.min(firstMax, secondMax);
        if (min > max) return "NONE";
        if (strict && (min == firstMin || max == firstMax)) return "TOUCHING";
        if (min == max) return "POINT:" + min;
        if (min == firstMin && max == firstMax) return "FIRST";
        if (min == secondMin && max == secondMax) return "SECOND";
        return min + ".." + max;
    }
}
