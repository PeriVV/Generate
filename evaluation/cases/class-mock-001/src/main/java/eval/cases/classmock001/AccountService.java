package eval.cases.classmock001;

public class AccountService {
    private final AccountRepository repository;
    private final AccountEvents events;
    public AccountService(AccountRepository repository, AccountEvents events) {
        this.repository = repository; this.events = events;
    }
    public String displayName(long id) {
        String name = repository.name(id);
        return name == null ? "unknown" : name.trim();
    }
    public boolean activate(long id) {
        if (!repository.exists(id)) return false;
        repository.activate(id); events.activated(id); return true;
    }
    protected void deactivate(long id) {
        repository.deactivate(id); events.deactivated(id);
    }
}

interface AccountRepository { String name(long id); boolean exists(long id); void activate(long id); void deactivate(long id); }
interface AccountEvents { void activated(long id); void deactivated(long id); }
