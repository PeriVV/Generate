package eval.cases.mock006;

public class OrderService {
    private final StockClient stock;
    private final OrderRepository orders;
    public OrderService(StockClient stock, OrderRepository orders) { this.stock = stock; this.orders = orders; }
    public long place(String sku, int quantity) {
        if (!stock.has(sku, quantity)) return -1L;
        return orders.create(sku, quantity);
    }
}

interface StockClient { boolean has(String sku, int quantity); }
interface OrderRepository { long create(String sku, int quantity); }
