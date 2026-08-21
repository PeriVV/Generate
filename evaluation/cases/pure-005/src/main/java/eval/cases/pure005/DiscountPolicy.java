package eval.cases.pure005;

public class DiscountPolicy {
    public int discount(int subtotal, boolean member) {
        if (member) return subtotal / 10;
        return 0;
    }
}
