package eval.cases.boundary004;

public class RangeValidator {
    public int length(int start, int end) {
        if (start < 0) throw new IllegalArgumentException("start");
        if (start >= end) throw new IllegalArgumentException("range");
        return end - start;
    }
}
