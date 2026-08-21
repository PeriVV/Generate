package eval.cases.mock010;

public class TransferService {
    private final AccountStore accounts;
    private final FraudClient fraud;
    private final TransferEvents events;
    public TransferService(AccountStore accounts, FraudClient fraud, TransferEvents events) {
        this.accounts = accounts; this.fraud = fraud; this.events = events;
    }
    public String transfer(String from, String to, int amount, boolean urgent) {
        if (from == null || to == null || from.equals(to)) throw new IllegalArgumentException("accounts");
        if (amount <= 0) throw new IllegalArgumentException("amount");
        int balance = accounts.balance(from);
        if (balance < amount) return "INSUFFICIENT";
        if (fraud.blocked(from, to, amount)) return "BLOCKED";
        int fee = urgent ? 10 : 0;
        if (balance < amount + fee) return "FEE_REQUIRED";
        accounts.debit(from, amount + fee);
        accounts.credit(to, amount);
        events.completed(from, to, amount);
        return urgent ? "URGENT" : "COMPLETED";
    }
}

interface AccountStore { int balance(String id); void debit(String id, int amount); void credit(String id, int amount); }
interface FraudClient { boolean blocked(String from, String to, int amount); }
interface TransferEvents { void completed(String from, String to, int amount); }
