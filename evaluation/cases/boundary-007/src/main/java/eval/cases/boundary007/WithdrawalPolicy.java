package eval.cases.boundary007;

public class WithdrawalPolicy {
    public int remaining(int balance, int amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount");
        if (amount > balance) throw new IllegalStateException("insufficient");
        return balance - amount;
    }
}
