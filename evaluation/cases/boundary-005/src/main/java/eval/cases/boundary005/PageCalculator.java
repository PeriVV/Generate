package eval.cases.boundary005;

public class PageCalculator {
    public int offset(int page, int size) {
        if (page < 1) throw new IllegalArgumentException("page");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size");
        return (page - 1) * size;
    }
}
