package eval.cases.classexisting002;

public class CouponService {
    public int apply(int subtotal, String code) {
        if (subtotal < 0) throw new IllegalArgumentException("subtotal");
        if ("SAVE10".equals(code)) return Math.max(0, subtotal - subtotal / 10);
        if ("LESS5".equals(code)) return Math.max(0, subtotal - 5);
        return subtotal;
    }
    public int apply(int subtotal, int fixedDiscount) {
        if (fixedDiscount < 0) throw new IllegalArgumentException("discount");
        return Math.max(0, subtotal - fixedDiscount);
    }
    static boolean supported(String code) {
        return "SAVE10".equals(code) || "LESS5".equals(code);
    }
    protected String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }
}
