package eval.cases.classmixed001;

public class OverloadedCalculator {
    public int add(int left, int right) { return left + right; }
    public long add(long left, long right) { return left + right; }
    static int multiply(int left, int right) { return left * right; }
    protected int divide(int left, int right) {
        if (right == 0) throw new ArithmeticException("zero");
        return left / right;
    }
}
