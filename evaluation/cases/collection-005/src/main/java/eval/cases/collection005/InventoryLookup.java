package eval.cases.collection005;

import java.util.Map;

public class InventoryLookup {
    public boolean available(Map<String, Integer> stock, String sku, int requested) {
        if (stock == null || sku == null) throw new IllegalArgumentException("input");
        if (requested <= 0) return false;
        return stock.getOrDefault(sku, 0) >= requested;
    }
}
