package eval.cases.classpure001;

public class PricingRules {
    public int subtotal(int price, int quantity) {
        if (price < 0 || quantity < 0) throw new IllegalArgumentException("input");
        return price * quantity;
    }

    public int shipping(int subtotal, boolean express) {
        if (subtotal >= 100) return express ? 10 : 0;
        return express ? 25 : 12;
    }

    protected int capDiscount(int discount, int subtotal) {
        return Math.max(0, Math.min(discount, subtotal));
    }
}
