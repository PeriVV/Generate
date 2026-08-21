package eval.cases.collection002;

import java.util.Map;

public class Counter {
    public int total(Map<String, Integer> values) {
        return values.values().stream().mapToInt(Integer::intValue).sum();
    }
}
