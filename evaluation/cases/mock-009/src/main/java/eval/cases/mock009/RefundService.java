package eval.cases.mock009;

public class RefundService {
    private final PurchaseRepository purchases;
    private final MoneyGateway gateway;
    private final AuditLog audit;
    public RefundService(PurchaseRepository purchases, MoneyGateway gateway, AuditLog audit) {
        this.purchases = purchases; this.gateway = gateway; this.audit = audit;
    }
    public String refund(long purchaseId, int cents, String reason) {
        Purchase purchase = purchases.find(purchaseId);
        if (purchase == null) return "NOT_FOUND";
        if (purchase.refunded()) return "ALREADY_REFUNDED";
        if (cents <= 0 || cents > purchase.paid()) throw new IllegalArgumentException("cents");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason");
        if (!gateway.refund(purchase.reference(), cents)) return "DECLINED";
        purchases.markRefunded(purchaseId, cents);
        audit.record(purchaseId, reason);
        return cents == purchase.paid() ? "FULL" : "PARTIAL";
    }
}

record Purchase(String reference, int paid, boolean refunded) {}
interface PurchaseRepository { Purchase find(long id); void markRefunded(long id, int cents); }
interface MoneyGateway { boolean refund(String reference, int cents); }
interface AuditLog { void record(long id, String reason); }
