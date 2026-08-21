package eval.cases.pure008;

public class AccessDecision {
    public String decide(boolean active, boolean admin, int failedAttempts, String region) {
        if (!active) return "DISABLED";
        if (failedAttempts >= 3) return "LOCKED";
        if (admin && "internal".equals(region)) return "ADMIN";
        if (admin) return "REVIEW";
        if ("blocked".equals(region)) return "DENIED";
        return "USER";
    }
}
