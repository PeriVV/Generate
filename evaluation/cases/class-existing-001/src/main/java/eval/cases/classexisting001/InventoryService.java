package eval.cases.classexisting001;

public class InventoryService {
    private final InventoryStore store;
    public InventoryService(InventoryStore store) { this.store = store; }
    public int available(String sku) { return Math.max(store.count(sku), 0); }
    public boolean reserve(String sku, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity");
        if (store.count(sku) < quantity) return false;
        store.remove(sku, quantity); return true;
    }
    public void replenish(String sku, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity");
        store.add(sku, quantity);
    }
}

interface InventoryStore { int count(String sku); void remove(String sku, int quantity); void add(String sku, int quantity); }
