package eval.cases.classmock003;

public class TicketService {
    private final TicketStore store;
    private final TicketNotifier notifier;
    public TicketService(TicketStore store, TicketNotifier notifier) { this.store = store; this.notifier = notifier; }
    public long open(String title) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title");
        long id = store.create(title.trim()); notifier.opened(id); return id;
    }
    public boolean close(long id) {
        if (!store.open(id)) return false;
        store.close(id); notifier.closed(id); return true;
    }
    public String status(long id) {
        return store.open(id) ? "OPEN" : "CLOSED";
    }
}

interface TicketStore { long create(String title); boolean open(long id); void close(long id); }
interface TicketNotifier { void opened(long id); void closed(long id); }
