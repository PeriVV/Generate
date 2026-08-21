package eval.cases.mock007;

public class PaymentService {
    private final PaymentGateway gateway;
    private final ReceiptStore receipts;
    public PaymentService(PaymentGateway gateway, ReceiptStore receipts) {
        this.gateway = gateway; this.receipts = receipts;
    }
    public String charge(String account, int cents) {
        if (cents <= 0) throw new IllegalArgumentException("cents");
        String transaction = gateway.charge(account, cents);
        if (transaction == null) throw new IllegalStateException("declined");
        receipts.save(transaction, cents);
        return transaction;
    }
}

interface PaymentGateway { String charge(String account, int cents); }
interface ReceiptStore { void save(String transaction, int cents); }
