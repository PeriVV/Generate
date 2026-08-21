package eval.cases.pure006;

public class ScoreBand {
    public String grade(int score) {
        return switch (score / 10) {
            case 10, 9 -> "A";
            case 8 -> "B";
            default -> "C";
        };
    }
}
