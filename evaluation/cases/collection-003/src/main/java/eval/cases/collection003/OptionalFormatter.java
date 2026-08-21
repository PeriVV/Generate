package eval.cases.collection003;

import java.util.Optional;

public class OptionalFormatter {
    public String display(Optional<String> value) {
        return value.map(String::trim).map(String::toUpperCase).orElse("UNKNOWN");
    }
}
