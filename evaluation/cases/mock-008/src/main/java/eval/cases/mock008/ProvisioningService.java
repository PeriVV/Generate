package eval.cases.mock008;

public class ProvisioningService {
    private final AccountRepository accounts;
    private final CapacityClient capacity;
    private final EventPublisher events;
    public ProvisioningService(AccountRepository accounts, CapacityClient capacity, EventPublisher events) {
        this.accounts = accounts; this.capacity = capacity; this.events = events;
    }
    public String provision(String account, int units, boolean trial) {
        if (account == null || account.isBlank()) throw new IllegalArgumentException("account");
        if (units < 1 || units > 100) throw new IllegalArgumentException("units");
        if (!accounts.exists(account)) return "MISSING";
        if (!capacity.reserve(units)) return "FULL";
        if (!trial) accounts.bill(account, units);
        events.publish(account, trial ? "TRIAL" : "ACTIVE");
        return "CREATED";
    }
}

interface AccountRepository { boolean exists(String account); void bill(String account, int units); }
interface CapacityClient { boolean reserve(int units); }
interface EventPublisher { void publish(String account, String state); }
