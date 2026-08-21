package eval.cases.collection006;

import java.util.List;

public class OrderSummarizer {
    public record Item(String sku, int quantity, int unitPrice) {}

    public int summarize(List<Item> items, String coupon, boolean priority) {
        if (items == null || items.isEmpty()) return 0;
        int total = 0;
        for (Item item : items) {
            if (item == null) continue;
            if (item.quantity() < 0 || item.unitPrice() < 0) throw new IllegalArgumentException("item");
            total += item.quantity() * item.unitPrice();
        }
        if ("SAVE10".equals(coupon)) total -= total / 10;
        else if (coupon != null && !coupon.isBlank()) total -= 5;
        if (priority) total += 15;
        return Math.max(total, 0);
    }
}
