package eval.cases.collection004;

import java.util.LinkedHashSet;
import java.util.List;

public class UniqueNames {
    public List<String> normalize(List<String> names) {
        if (names == null) throw new IllegalArgumentException("names");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) unique.add(name.trim().toLowerCase());
        }
        return List.copyOf(unique);
    }
}
