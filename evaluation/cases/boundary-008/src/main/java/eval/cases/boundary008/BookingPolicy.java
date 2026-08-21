package eval.cases.boundary008;

public class BookingPolicy {
    public int quote(int guests, int nights, boolean weekend, String tier) {
        if (guests < 1 || guests > 8) throw new IllegalArgumentException("guests");
        if (nights < 1 || nights > 30) throw new IllegalArgumentException("nights");
        int price = guests * nights * 40;
        if (weekend) price += nights * 20;
        if ("GOLD".equals(tier)) price -= price / 10;
        else if ("BLOCKED".equals(tier)) throw new IllegalStateException("tier");
        if (nights >= 7) price -= 50;
        return Math.max(price, 0);
    }
}
