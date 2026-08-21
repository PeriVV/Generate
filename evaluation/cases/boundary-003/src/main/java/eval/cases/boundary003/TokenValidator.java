package eval.cases.boundary003;

public class TokenValidator {
    public String normalize(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token");
        return token.trim().toLowerCase();
    }
}
