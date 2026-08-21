package eval.cases.boundary006;

public class PasswordPolicy {
    public boolean validate(String password) {
        if (password == null) throw new IllegalArgumentException("password");
        if (password.length() < 8 || password.length() > 32) return false;
        return password.chars().anyMatch(Character::isDigit);
    }
}
