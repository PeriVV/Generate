package eval.cases.mock005;

public class QuotaService {
    private final QuotaStore store;
    public QuotaService(QuotaStore store) { this.store = store; }
    public int reserve(String account, int requested) {
        if (requested <= 0) throw new IllegalArgumentException("requested");
        int available = store.available(account);
        if (available < requested) return 0;
        store.consume(account, requested);
        return requested;
    }
}

interface QuotaStore { int available(String account); void consume(String account, int amount); }
