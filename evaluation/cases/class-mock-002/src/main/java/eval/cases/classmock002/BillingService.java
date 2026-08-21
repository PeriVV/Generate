package eval.cases.classmock002;

public class BillingService {
    private final RateRepository rates;
    private final InvoiceStore invoices;
    public BillingService(RateRepository rates, InvoiceStore invoices) {
        this.rates = rates; this.invoices = invoices;
    }
    public int quote(String plan, int units) {
        if (units < 0) throw new IllegalArgumentException("units");
        return rates.unitPrice(plan) * units;
    }
    public long invoice(String account, int amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount");
        return invoices.create(account, amount);
    }
    static int tax(int amount, boolean exempt) {
        return exempt ? 0 : amount / 10;
    }
}

interface RateRepository { int unitPrice(String plan); }
interface InvoiceStore { long create(String account, int amount); }
