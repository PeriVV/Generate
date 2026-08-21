package eval.cases.fixture003;

public class BoxService {
    public String unwrap(Box<String> box) {
        if (box == null || box.value() == null) return "empty";
        return box.value().trim();
    }
}

record Box<T>(T value) {}
